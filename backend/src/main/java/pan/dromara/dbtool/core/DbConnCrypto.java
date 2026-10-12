package pan.dromara.dbtool.core;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

/**
 * 数据库连接密码 加解密工具
 * <p>AES 加密存储, 密钥固定(项目内部使用)</p>
 *
 * @author doubao
 */
public class DbConnCrypto {

    /**
     * AES 密钥(16字节)
     */
    private static final String KEY = "dbtool-crypto-16";

    private static final AES AES = SecureUtil.aes(KEY.getBytes());

    private DbConnCrypto() {
    }

    /**
     * 加密
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return AES.encryptBase64(plain);
    }

    /**
     * 解密
     */
    public static String decrypt(String cipher) {
        if (cipher == null || cipher.isEmpty()) {
            return "";
        }
        try {
            return AES.decryptStr(cipher);
        } catch (Exception e) {
            // 解密失败说明可能是明文, 原样返回
            return cipher;
        }
    }
}
