/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingSecret.java
 * @description Browser 접근 자격의 난수 생성과 저장용 단방향 해시를 제공합니다.
 */
package org.jn.waitingroom.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 원문을 영속화하지 않고 256-bit 비밀값을 생성하는 공통 규칙입니다. */
public final class WaitingSecret {
    private static final SecureRandom RANDOM = new SecureRandom();

    private WaitingSecret() {
    }

    /** URL fragment와 cookie에 전달할 무패딩 base64url 비밀값을 생성합니다. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Redis key와 상태 JSON에 저장할 SHA-256 해시를 구합니다. */
    public static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
