package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/** 使用 DEX 中的真实名称；JADX 会把 s6 合并到擦除类型的 onBindItemView 桥接方法。 */
internal data class PollBindingContract(val bind: Method, val data: Field) {
    companion object {
        fun resolve(cell: Class<*>, poll: Class<*>): PollBindingContract {
            fun data(type: Class<*>): Field? {
                var current: Class<*>? = type
                while (current != null) {
                    current.declaredFields.firstOrNull { ExtensionSymbols.fieldNamed(it, "LL") && it.type == poll }?.let {
                        return it.apply { isAccessible = true }
                    }
                    current = current.superclass
                }
                return null
            }
            val candidates = cell.declaredMethods.filter {
                ExtensionSymbols.methodNamed(it, "s6") && it.parameterCount == 1 && it.returnType == Void.TYPE
            }.mapNotNull { method -> data(method.parameterTypes[0])?.let { PollBindingContract(method, it) } }
            check(candidates.size == 1) { "PollCell DEX s6(PollItem):void contract candidates=${candidates.size}" }
            return candidates.single().also { it.bind.isAccessible = true }
        }
    }
}
