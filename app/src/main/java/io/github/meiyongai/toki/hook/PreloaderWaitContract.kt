package io.github.meiyongai.toki.subtitle.hook

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import java.util.zip.ZipFile

/** Accept only the exact tiny loop observed in the on-device stack capture. */
internal object PreloaderWaitContract {
    data class Contract(val owner: String, val method: String, val markerOwner: String, val marker: String)
    fun verify(path: String): Contract? {
        ZipFile(path).use { archive ->
            for (entry in archive.entries()) {
                if (!entry.name.matches(Regex("classes[0-9]*\\.dex"))) continue
                val dex = archive.getInputStream(entry).use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it.buffered()) }
                val type = dex.classes.firstOrNull { it.type == "Lcom/ss/android/ugc/aweme/experiment/appinit/PreloaderInAppInit;" } ?: continue
                val matches = type.methods.filter { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/Application;") }
                for (method in matches) {
                    val impl = method.implementation ?: continue
                    if (impl.tryBlocks.isNotEmpty() || method.accessFlags and 8 == 0) continue
                    val code = impl.instructions.toList()
                    if (code.map { it.opcode } != listOf(Opcode.INVOKE_VIRTUAL,Opcode.MOVE_RESULT_OBJECT,Opcode.IF_EQZ,Opcode.SGET_WIDE,Opcode.CONST_WIDE_16,Opcode.CMP_LONG,Opcode.IF_EQZ,Opcode.RETURN_VOID)) continue
                    if ((code[4] as? WideLiteralInstruction)?.wideLiteral != 0L) continue
                    val call = (code[0] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (call.definingClass != "Landroid/content/ContextWrapper;" || call.name != "getBaseContext" || call.returnType != "Landroid/content/Context;") continue
                    val field = (code[3] as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if (field.type != "J") continue
                    val offsets = IntArray(code.size);for (i in 1 until code.size) offsets[i] = offsets[i-1]+code[i-1].codeUnits
                    if (listOf(2,6).any { offsets[it]+(code[it] as OffsetInstruction).codeOffset != 0 }) continue
                    return Contract(type.type.removePrefix("L").removeSuffix(";").replace('/','.'),method.name,
                        field.definingClass.removePrefix("L").removeSuffix(";").replace('/','.'),field.name)
                }
                return null
            }
        }
        return null
    }
}
