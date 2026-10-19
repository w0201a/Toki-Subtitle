package io.github.meiyongai.toki.subtitle.hook

import java.util.Properties

/** Native candidate search is an optimization; missing targets get independent full local verification. */
internal object HostCoreScanner {
    const val REVISION = "local-recovery-v1"
    fun scan(paths: List<String>, rules: String, progress: (Int, Int) -> Unit): Properties = ScanWorkers.open().use { recover(rules,
        primary = { HostNativeIndex.scan(paths, rules) { done, total -> progress((done.toLong() * 600 / total.coerceAtLeast(1)).toInt(), 1000) } },
        secondary = { selected -> HostDexIndex.scan(paths, selected) { done, total -> progress(600 + (done.toLong() * 400 / total.coerceAtLeast(1)).toInt(), 1000) } }
    ).also { progress(1000, 1000) } }

    internal fun recover(rules: String, primary: () -> Properties, secondary: (String) -> Properties): Properties {
        val first = try { primary() } catch (_: Exception) { Properties() } catch (_: LinkageError) { Properties() }
        val symbols = rules.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.substringBefore('\t') }.toSet()
        val missing = symbols.filter { first.getProperty(it).isNullOrBlank() }.toSet()
        if (missing.isEmpty()) return first
        val subset = rules.lineSequence().filter { it.substringBefore('\t') in missing }.joinToString("\n", postfix = "\n")
        val retry = try { secondary(subset) } catch (error: Exception) {
            Properties().apply { missing.forEach { setProperty("error.$it", "local verification failed: ${error.javaClass.simpleName}") } }
        }
        return Properties().apply {
            putAll(first)
            for (symbol in missing) {
                stringPropertyNames().filter { it == symbol || it == "error.$symbol" || it.startsWith("member.$symbol.") || it.startsWith("members.$symbol.") }.forEach(::remove)
                val keys = retry.stringPropertyNames().filter { it == symbol || it == "error.$symbol" || it.startsWith("member.$symbol.") || it.startsWith("members.$symbol.") }
                keys.forEach { setProperty(it, retry.getProperty(it)) }
                if (getProperty(symbol).isNullOrBlank() && getProperty("error.$symbol").isNullOrBlank()) setProperty("error.$symbol", "local verification returned no contract")
            }
        }
    }
}
