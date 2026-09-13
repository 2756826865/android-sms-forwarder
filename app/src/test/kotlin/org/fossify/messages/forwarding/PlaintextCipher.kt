package org.fossify.messages.forwarding

/**
 * 直通实现：不做任何加解密，原样返回。
 *
 * 仅存在于单元测试源集，供 JVM 单测注入 [MultiForwardConfig] 使用
 * （单测环境没有 AndroidKeyStore，[AndroidKeystoreCipher] 会直接抛 KeyStoreException）。
 *
 * 放在 test 源集而非 main 源集，是为了从物理上杜绝生产代码引用到"不加密"的实现——
 * 一旦被误用，凭据将以明文落盘，且没有任何编译期警告能拦住。
 */
object PlaintextCipher : CredentialCipher {
    override fun encrypt(value: String): String = value

    override fun decrypt(value: String): String = value

    /** 直通实现下没有任何值属于密文形态，恒为 false。 */
    override fun looksLikeCiphertext(value: String): Boolean = false
}
