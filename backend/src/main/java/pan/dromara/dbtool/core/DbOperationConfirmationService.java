package pan.dromara.dbtool.core;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues short-lived, single-use approvals for high-risk SQL execution.
 */
@Component
@RequiredArgsConstructor
public class DbOperationConfirmationService {

    private static final long TOKEN_TTL_MILLIS = 2 * 60 * 1000L;
    private static final String KEY_PREFIX = "dbtool:operation:confirmation:";
    private final RedissonClient redissonClient;

    public String issue(Long connId, String sql, String userId) {
        if (connId == null || sql == null || userId == null || userId.isBlank()) {
            throw new ServiceException("无法为无效的连接、SQL 或用户签发确认凭证");
        }
        String token = UUID.randomUUID().toString();
        JSONObject value = new JSONObject();
        value.set("connId", connId);
        value.set("sqlDigest", SecureUtil.sha256(sql));
        value.set("userId", userId);
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + token, StringCodec.INSTANCE);
        bucket.set(value.toString(), Duration.ofMillis(TOKEN_TTL_MILLIS));
        return token;
    }

    public boolean consume(String token, Long connId, String sql, String userId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + token, StringCodec.INSTANCE);
        String stored = bucket.getAndDelete();
        if (stored == null) {
            return false;
        }
        JSONObject confirmation = JSONUtil.parseObj(stored);
        return Objects.equals(connId, confirmation.getLong("connId"))
            && Objects.equals(SecureUtil.sha256(sql), confirmation.getStr("sqlDigest"))
            && Objects.equals(userId, confirmation.getStr("userId"));
    }
}
