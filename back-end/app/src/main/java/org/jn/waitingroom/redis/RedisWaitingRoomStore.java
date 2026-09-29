/**
 * @author jeongnam
 * @since 2026-09-21
 * @file RedisWaitingRoomStore.java
 * @description 요청 상태와 대기열 Sorted Set을 Redis Lua로 원자 변경합니다.
 */
package org.jn.waitingroom.redis;

import org.jn.waitingroom.domain.WaitingRequestState;
import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 서비스 단위 Redis Key를 만들고 Lua script로 관련 Key를 함께 변경합니다.
 */
@Repository
public class RedisWaitingRoomStore {
    private static final String SERVICE_ID_PATTERN = "[a-z0-9-]+";

    private static final DefaultRedisScript<List> REGISTER_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local existing = redis.call('GET', KEYS[1])
            if existing then
                local decoded, state = pcall(cjson.decode, existing)
                if not decoded then
                    return {'STORE_ERROR', ''}
                end
                if state.serviceId == ARGV[3] and state.payloadFingerprint == ARGV[4] then
                    return {'REPLAYED', existing}
                end
                return {'CONFLICT', existing}
            end

            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            local state = cjson.decode(ARGV[1])
            state.createdAt = now
            local encoded = cjson.encode(state)

            redis.call('SET', KEYS[1], encoded, 'PX', ARGV[2])
            redis.call('ZADD', KEYS[2], 'NX', now, ARGV[5])
            redis.call('ZADD', KEYS[3], 'NX', now, ARGV[5])
            return {'CREATED', encoded}
            """);

    private static final DefaultRedisScript<List> ADMIT_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset')
                    or not valid_type(KEYS[4], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local first = redis.call('ZRANGE', KEYS[2], 0, 0)[1]
            if not first then
                return {'EMPTY', ''}
            end
            if first ~= ARGV[1] then
                return {'RETRY', ''}
            end
            if redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[2]) then
                return {'FULL', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            if not encoded then
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('ZREM', KEYS[3], ARGV[1])
                return {'CLEANED', ''}
            end

            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            local decoded, state = pcall(cjson.decode, encoded)
            if not decoded
                    or type(state) ~= 'table'
                    or type(state.reservationRequestId) ~= 'string'
                    or type(state.serviceId) ~= 'string'
                    or type(state.status) ~= 'string'
                    or type(state.createdAt) ~= 'number'
                    or (state.redirectTargetId ~= nil and type(state.redirectTargetId) ~= 'string')
                    or (state.payloadFingerprint ~= nil and type(state.payloadFingerprint) ~= 'string')
                    or (state.admittedAt ~= nil and type(state.admittedAt) ~= 'number')
                    or (state.enteredAt ~= nil and type(state.enteredAt) ~= 'number')
                    or (state.completedAt ~= nil and type(state.completedAt) ~= 'number')
                    or (state.expiredAt ~= nil and type(state.expiredAt) ~= 'number')
                    or (state.expirationReason ~= nil and type(state.expirationReason) ~= 'string')
                    or (state.nextPollAllowedAt ~= nil and type(state.nextPollAllowedAt) ~= 'number')
                    or (state.status ~= 'WAITING'
                        and state.status ~= 'ADMITTED'
                        and state.status ~= 'ENTERED'
                        and state.status ~= 'EXPIRED'
                        and state.status ~= 'CANCELLED')
                    or (state.status == 'WAITING'
                        and (type(state.redirectTargetId) ~= 'string'
                            or state.redirectTargetId == ''
                            or type(state.payloadFingerprint) ~= 'string'
                            or state.payloadFingerprint == '')) then
                redis.call('SET', KEYS[5], encoded, 'PX', ARGV[6])
                state = {
                    reservationRequestId = ARGV[1],
                    serviceId = ARGV[7],
                    status = 'EXPIRED',
                    createdAt = now,
                    expiredAt = now,
                    expirationReason = 'STATE_CORRUPTED'
                }
                encoded = cjson.encode(state)
                redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('ZREM', KEYS[3], ARGV[1])
                return {'CLEANED', encoded}
            end

            local function expire_inconsistent()
                state.status = 'EXPIRED'
                state.expiredAt = now
                state.expirationReason = 'INTERNAL_INCONSISTENCY'
                encoded = cjson.encode(state)
                redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('ZREM', KEYS[3], ARGV[1])
                return {'CLEANED', encoded}
            end

            if state.reservationRequestId ~= ARGV[1] or state.serviceId ~= ARGV[7] then
                return expire_inconsistent()
            end
            if state.status ~= 'WAITING' then
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('ZREM', KEYS[3], ARGV[1])
                return {'CLEANED', encoded}
            end

            local waitingScore = redis.call('ZSCORE', KEYS[2], ARGV[1])
            local heartbeatScore = redis.call('ZSCORE', KEYS[3], ARGV[1])
            if not waitingScore or not heartbeatScore then
                return expire_inconsistent()
            end
            if now - tonumber(heartbeatScore) >= tonumber(ARGV[4])
                    or now - tonumber(waitingScore) >= tonumber(ARGV[5]) then
                return {'RETRY', ''}
            end

            state.status = 'ADMITTED'
            state.admittedAt = now
            encoded = cjson.encode(state)

            redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('ZADD', KEYS[4], now + tonumber(ARGV[3]), ARGV[1])
            return {'APPLIED', encoded}
            """);

    private static final DefaultRedisScript<List> ENTER_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            local function record_release(key, reservationRequestId, now, etaWindowMs)
                local added = redis.pcall('ZADD', key, now, reservationRequestId)
                local pruned = redis.pcall('ZREMRANGEBYSCORE', key, '-inf', '(' .. (now - etaWindowMs))
                local expired = redis.pcall('PEXPIRE', key, etaWindowMs * 2)
                if (type(added) == 'table' and added['err'])
                        or (type(pruned) == 'table' and pruned['err'])
                        or (type(expired) == 'table' and expired['err']) then
                    redis.pcall('DEL', key)
                end
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            local slotExpiry = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if not encoded then
                return {'NOT_FOUND', ''}
            end
            if not slotExpiry then
                return {'CONFLICT', ''}
            end
            local decoded, state = pcall(cjson.decode, encoded)
            if not decoded then
                return {'STORE_ERROR', ''}
            end

            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            if tonumber(slotExpiry) <= now then
                if state.status == 'ADMITTED' then
                    state.status = 'EXPIRED'
                    state.expiredAt = now
                    state.expirationReason = 'ADMISSION_TIMEOUT'
                    encoded = cjson.encode(state)
                    redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                    local removed = redis.call('ZREM', KEYS[2], ARGV[1])
                    if removed == 1 then
                        record_release(KEYS[3], ARGV[1], now, tonumber(ARGV[4]))
                        redis.call('PUBLISH', ARGV[3], ARGV[1])
                    end
                    return {'ADMISSION_EXPIRED', encoded}
                end
                return {'CONFLICT', encoded}
            end
            if state.status == 'ENTERED' and state.completedAt == nil then
                return {'REPLAYED', encoded}
            end
            if state.status ~= 'ADMITTED' then
                return {'CONFLICT', encoded}
            end

            state.status = 'ENTERED'
            state.enteredAt = now
            encoded = cjson.encode(state)
            redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
            redis.call('ZADD', KEYS[2], 'XX', now + tonumber(ARGV[2]), ARGV[1])
            return {'APPLIED', encoded}
            """);

    private static final DefaultRedisScript<List> COMPLETE_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            local function record_release(key, reservationRequestId, now, etaWindowMs)
                local added = redis.pcall('ZADD', key, now, reservationRequestId)
                local pruned = redis.pcall('ZREMRANGEBYSCORE', key, '-inf', '(' .. (now - etaWindowMs))
                local expired = redis.pcall('PEXPIRE', key, etaWindowMs * 2)
                if (type(added) == 'table' and added['err'])
                        or (type(pruned) == 'table' and pruned['err'])
                        or (type(expired) == 'table' and expired['err']) then
                    redis.pcall('DEL', key)
                end
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            if not encoded then
                return {'NOT_FOUND', ''}
            end
            local decoded, state = pcall(cjson.decode, encoded)
            if not decoded then
                return {'STORE_ERROR', ''}
            end
            if state.enteredAt == nil then
                return {'CONFLICT', encoded}
            end
            if state.completedAt ~= nil then
                return {'REPLAYED', encoded}
            end
            if state.status ~= 'ENTERED' and state.status ~= 'EXPIRED' then
                return {'CONFLICT', encoded}
            end

            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            state.completedAt = now
            encoded = cjson.encode(state)
            redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
            local removed = redis.call('ZREM', KEYS[2], ARGV[1])
            if removed == 1 then
                record_release(KEYS[3], ARGV[1], now, tonumber(ARGV[3]))
                redis.call('PUBLISH', ARGV[2], ARGV[1])
            end
            return {'APPLIED', encoded}
            """);

    private static final DefaultRedisScript<List> CANCEL_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            local function record_release(key, reservationRequestId, now, etaWindowMs)
                local added = redis.pcall('ZADD', key, now, reservationRequestId)
                local pruned = redis.pcall('ZREMRANGEBYSCORE', key, '-inf', '(' .. (now - etaWindowMs))
                local expired = redis.pcall('PEXPIRE', key, etaWindowMs * 2)
                if (type(added) == 'table' and added['err'])
                        or (type(pruned) == 'table' and pruned['err'])
                        or (type(expired) == 'table' and expired['err']) then
                    redis.pcall('DEL', key)
                end
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset')
                    or not valid_type(KEYS[4], 'zset')
                    or not valid_type(KEYS[5], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            if not encoded then
                return {'NOT_FOUND', ''}
            end
            local decoded, state = pcall(cjson.decode, encoded)
            if not decoded then
                return {'STORE_ERROR', ''}
            end
            if state.status == 'CANCELLED' then
                return {'REPLAYED', encoded}
            end
            if state.status ~= 'WAITING' and state.status ~= 'ADMITTED' then
                return {'CONFLICT', encoded}
            end

            local previousStatus = state.status
            state.status = 'CANCELLED'
            encoded = cjson.encode(state)
            redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[3], ARGV[1])
            local removed = redis.call('ZREM', KEYS[4], ARGV[1])
            if previousStatus == 'ADMITTED' and removed == 1 then
                local currentTime = redis.call('TIME')
                local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
                record_release(KEYS[5], ARGV[1], now, tonumber(ARGV[3]))
                redis.call('PUBLISH', ARGV[2], ARGV[1])
            end
            return {'APPLIED', encoded}
            """);

    private static final DefaultRedisScript<List> POLL_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset')
                    or not valid_type(KEYS[4], 'zset')
                    or not valid_type(KEYS[5], 'zset') then
                return {'STORE_ERROR', '', '', '', '', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            if not encoded then
                return {'NOT_FOUND', '', '', '', '', ''}
            end
            local decoded, state = pcall(cjson.decode, encoded)
            if not decoded or state.serviceId ~= ARGV[2] then
                return {'STORE_ERROR', '', '', '', '', ''}
            end
            if state.status ~= 'WAITING' then
                return {'FOUND', encoded, '', '0', '', ''}
            end

            local waitingScore = redis.call('ZSCORE', KEYS[2], ARGV[1])
            local heartbeatScore = redis.call('ZSCORE', KEYS[3], ARGV[1])
            if not waitingScore or not heartbeatScore then
                return {'STORE_ERROR', encoded, '', '', '', ''}
            end

            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            local remainingHeartbeat = tonumber(heartbeatScore) + tonumber(ARGV[7]) - now
            if remainingHeartbeat <= 0 then
                state.status = 'EXPIRED'
                state.expiredAt = now
                state.expirationReason = 'HEARTBEAT_TIMEOUT'
                encoded = cjson.encode(state)
                redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('ZREM', KEYS[3], ARGV[1])
                return {'FOUND', encoded, '', tostring(redis.call('ZCARD', KEYS[2])), '', ''}
            end

            local nextPollAllowedAt = tonumber(state.nextPollAllowedAt)
            if nextPollAllowedAt and nextPollAllowedAt > now then
                local safetyMargin = tonumber(ARGV[9])
                if remainingHeartbeat <= safetyMargin then
                    state.status = 'EXPIRED'
                    state.expiredAt = now
                    state.expirationReason = 'HEARTBEAT_TIMEOUT'
                    encoded = cjson.encode(state)
                    redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                    redis.call('ZREM', KEYS[2], ARGV[1])
                    redis.call('ZREM', KEYS[3], ARGV[1])
                    return {'FOUND', encoded, '', tostring(redis.call('ZCARD', KEYS[2])), '', ''}
                end

                local retryAfter = math.min(
                    nextPollAllowedAt - now,
                    tonumber(ARGV[8]),
                    remainingHeartbeat - safetyMargin
                )
                return {'RATE_LIMITED', encoded, '', '', tostring(math.max(1, retryAfter)), ''}
            end

            local rank = redis.call('ZRANK', KEYS[2], ARGV[1])
            local position = rank + 1
            local availableSlots = math.max(0, tonumber(ARGV[6]) - redis.call('ZCARD', KEYS[4]))
            local requiredReleases = math.max(0, position - availableSlots)
            local batchDelaySeconds = math.ceil(position / tonumber(ARGV[14])) * tonumber(ARGV[15]) / 1000
            local estimatedWaitSeconds = nil

            if requiredReleases == 0 then
                estimatedWaitSeconds = math.ceil(batchDelaySeconds)
            else
                local etaWindowMs = tonumber(ARGV[10])
                redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', '(' .. (now - etaWindowMs))
                local releaseSampleCount = redis.call('ZCARD', KEYS[5])
                if releaseSampleCount >= tonumber(ARGV[11]) then
                    local oldestSample = redis.call('ZRANGE', KEYS[5], 0, 0, 'WITHSCORES')
                    local observationMs = math.max(
                        tonumber(ARGV[12]),
                        math.min(etaWindowMs, math.max(0, now - tonumber(oldestSample[2])))
                    )
                    local releaseRatePerSecond = releaseSampleCount / (observationMs / 1000)
                    local releaseDelaySeconds = requiredReleases / releaseRatePerSecond
                    estimatedWaitSeconds = math.ceil(math.max(batchDelaySeconds, releaseDelaySeconds))
                elseif tonumber(ARGV[13]) > 0 then
                    local releaseDelaySeconds = requiredReleases / tonumber(ARGV[13])
                    estimatedWaitSeconds = math.ceil(math.max(batchDelaySeconds, releaseDelaySeconds))
                end
            end

            local selectedPollDelay = tonumber(ARGV[5])
            if estimatedWaitSeconds and estimatedWaitSeconds < 60 then
                selectedPollDelay = tonumber(ARGV[3])
            elseif estimatedWaitSeconds and estimatedWaitSeconds < 3600 then
                selectedPollDelay = tonumber(ARGV[4])
            end

            state.nextPollAllowedAt = now + selectedPollDelay
            encoded = cjson.encode(state)
            redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
            redis.call('ZADD', KEYS[3], 'XX', now, ARGV[1])
            local waitingCount = redis.call('ZCARD', KEYS[2])
            local encodedEta = estimatedWaitSeconds and tostring(estimatedWaitSeconds) or ''
            return {'FOUND', encoded, tostring(position), tostring(waitingCount), tostring(selectedPollDelay), encodedEta}
            """);

    private static final DefaultRedisScript<List> EXPIRE_ACTIVE_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            local function record_release(key, reservationRequestId, now, etaWindowMs)
                local added = redis.pcall('ZADD', key, now, reservationRequestId)
                local pruned = redis.pcall('ZREMRANGEBYSCORE', key, '-inf', '(' .. (now - etaWindowMs))
                local expired = redis.pcall('PEXPIRE', key, etaWindowMs * 2)
                if (type(added) == 'table' and added['err'])
                        or (type(pruned) == 'table' and pruned['err'])
                        or (type(expired) == 'table' and expired['err']) then
                    redis.pcall('DEL', key)
                end
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[4], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local slotExpiry = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if not slotExpiry then
                return {'EMPTY', ''}
            end
            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            if tonumber(slotExpiry) > now then
                return {'RETRY', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            if not encoded then
                local removed = redis.call('ZREM', KEYS[2], ARGV[1])
                if removed == 1 then
                    record_release(KEYS[4], ARGV[1], now, tonumber(ARGV[5]))
                    redis.call('PUBLISH', ARGV[2], ARGV[1])
                end
                return {'APPLIED', ''}
            end
            local decoded, state = pcall(cjson.decode, encoded)
            if not decoded
                    or type(state) ~= 'table'
                    or state.reservationRequestId ~= ARGV[1]
                    or state.serviceId ~= ARGV[4]
                    or type(state.status) ~= 'string'
                    or type(state.createdAt) ~= 'number'
                    or (state.redirectTargetId ~= nil and type(state.redirectTargetId) ~= 'string')
                    or (state.payloadFingerprint ~= nil and type(state.payloadFingerprint) ~= 'string')
                    or (state.admittedAt ~= nil and type(state.admittedAt) ~= 'number')
                    or (state.enteredAt ~= nil and type(state.enteredAt) ~= 'number')
                    or (state.completedAt ~= nil and type(state.completedAt) ~= 'number')
                    or (state.expiredAt ~= nil and type(state.expiredAt) ~= 'number')
                    or (state.expirationReason ~= nil and type(state.expirationReason) ~= 'string')
                    or (state.nextPollAllowedAt ~= nil and type(state.nextPollAllowedAt) ~= 'number')
                    or (state.status ~= 'WAITING'
                        and state.status ~= 'ADMITTED'
                        and state.status ~= 'ENTERED'
                        and state.status ~= 'EXPIRED'
                        and state.status ~= 'CANCELLED') then
                redis.call('SET', KEYS[3], encoded, 'PX', ARGV[3])
                state = {
                    reservationRequestId = ARGV[1],
                    serviceId = ARGV[4],
                    status = 'EXPIRED',
                    createdAt = now,
                    expiredAt = now,
                    expirationReason = 'STATE_CORRUPTED'
                }
                encoded = cjson.encode(state)
                redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                local removed = redis.call('ZREM', KEYS[2], ARGV[1])
                if removed == 1 then
                    record_release(KEYS[4], ARGV[1], now, tonumber(ARGV[5]))
                    redis.call('PUBLISH', ARGV[2], ARGV[1])
                end
                return {'CLEANED', encoded}
            end

            local reason = nil
            if state.status == 'ADMITTED' then
                reason = 'ADMISSION_TIMEOUT'
            elseif state.status == 'ENTERED' and state.completedAt == nil then
                reason = 'SESSION_TIMEOUT'
            end
            if reason then
                state.status = 'EXPIRED'
                state.expiredAt = now
                state.expirationReason = reason
                encoded = cjson.encode(state)
                redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
            end

            local removed = redis.call('ZREM', KEYS[2], ARGV[1])
            if removed == 1 then
                record_release(KEYS[4], ARGV[1], now, tonumber(ARGV[5]))
                redis.call('PUBLISH', ARGV[2], ARGV[1])
            end
            return {'APPLIED', encoded}
            """);

    private static final DefaultRedisScript<List> EXPIRE_WAITING_SCRIPT = script("""
            local function valid_type(key, expected)
                local current = redis.call('TYPE', key)['ok']
                return current == 'none' or current == expected
            end

            if not valid_type(KEYS[1], 'string')
                    or not valid_type(KEYS[2], 'zset')
                    or not valid_type(KEYS[3], 'zset') then
                return {'STORE_ERROR', ''}
            end

            local waitingScore = redis.call('ZSCORE', KEYS[2], ARGV[1])
            local heartbeatScore = redis.call('ZSCORE', KEYS[3], ARGV[1])
            if not waitingScore and not heartbeatScore then
                return {'EMPTY', ''}
            end
            local currentTime = redis.call('TIME')
            local now = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            local reason = nil
            if waitingScore and now - tonumber(waitingScore) >= tonumber(ARGV[2]) then
                reason = 'MAX_WAIT_DURATION'
            elseif heartbeatScore and now - tonumber(heartbeatScore) >= tonumber(ARGV[3]) then
                reason = 'HEARTBEAT_TIMEOUT'
            end
            if not reason then
                return {'RETRY', ''}
            end

            local encoded = redis.call('GET', KEYS[1])
            if encoded then
                local decoded, state = pcall(cjson.decode, encoded)
                if not decoded
                        or type(state) ~= 'table'
                        or state.reservationRequestId ~= ARGV[1]
                        or state.serviceId ~= ARGV[5]
                        or type(state.status) ~= 'string'
                        or type(state.createdAt) ~= 'number'
                        or (state.redirectTargetId ~= nil and type(state.redirectTargetId) ~= 'string')
                        or (state.payloadFingerprint ~= nil and type(state.payloadFingerprint) ~= 'string')
                        or (state.admittedAt ~= nil and type(state.admittedAt) ~= 'number')
                        or (state.enteredAt ~= nil and type(state.enteredAt) ~= 'number')
                        or (state.completedAt ~= nil and type(state.completedAt) ~= 'number')
                        or (state.expiredAt ~= nil and type(state.expiredAt) ~= 'number')
                        or (state.expirationReason ~= nil and type(state.expirationReason) ~= 'string')
                        or (state.nextPollAllowedAt ~= nil and type(state.nextPollAllowedAt) ~= 'number')
                        or (state.status ~= 'WAITING'
                            and state.status ~= 'ADMITTED'
                            and state.status ~= 'ENTERED'
                            and state.status ~= 'EXPIRED'
                            and state.status ~= 'CANCELLED') then
                    redis.call('SET', KEYS[4], encoded, 'PX', ARGV[4])
                    state = {
                        reservationRequestId = ARGV[1],
                        serviceId = ARGV[5],
                        status = 'EXPIRED',
                        createdAt = now,
                        expiredAt = now,
                        expirationReason = 'STATE_CORRUPTED'
                    }
                    encoded = cjson.encode(state)
                    redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                    redis.call('ZREM', KEYS[2], ARGV[1])
                    redis.call('ZREM', KEYS[3], ARGV[1])
                    return {'CLEANED', encoded}
                end
                if state.status == 'WAITING' then
                    state.status = 'EXPIRED'
                    state.expiredAt = now
                    state.expirationReason = reason
                    encoded = cjson.encode(state)
                    redis.call('SET', KEYS[1], encoded, 'KEEPTTL')
                end
            end

            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[3], ARGV[1])
            return {'APPLIED', encoded or ''}
            """);

    private static final DefaultRedisScript<Long> RELEASE_LEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Redis 문자열 연산과 JSON 직렬화기를 주입받습니다.
     *
     * @param redis Redis 문자열 연산 템플릿
     * @param objectMapper 요청 상태 JSON 직렬화기
     */
    public RedisWaitingRoomStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = Objects.requireNonNull(redis);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * 대기 요청과 최초 heartbeat를 한 번에 등록합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param payloadFingerprint 멱등 요청 payload 지문
     * @param redirectTargetId 입장 시 사용할 redirect 대상 식별자
     * @param requestTtl 요청 상태 보관시간
     * @return 신규 등록, 기존 결과 재사용 또는 payload 충돌 결과
     */
    public WriteResult register(
            String serviceId,
            String reservationRequestId,
            String payloadFingerprint,
            String redirectTargetId,
            Duration requestTtl
    ) {
        validateIdentifiers(serviceId, reservationRequestId);
        var state = new WaitingRequestState(
                reservationRequestId,
                serviceId,
                WaitingRequestStatus.WAITING,
                redirectTargetId,
                payloadFingerprint,
                0,
                null,
                null,
                null,
                null,
                null,
                null
        );

        return execute(
                REGISTER_SCRIPT,
                List.of(requestKey(serviceId, reservationRequestId), waitingKey(serviceId), heartbeatKey(serviceId)),
                writeJson(state),
                Long.toString(requestTtl.toMillis()),
                serviceId,
                payloadFingerprint,
                reservationRequestId
        );
    }

    /**
     * 대기열 선두 요청 하나에 빈 활성 슬롯을 원자적으로 할당합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param maxConcurrentUsers 서비스의 최대 활성 사용자 수
     * @param admissionTimeout 입장 허용 상태의 활성 슬롯 유지시간
     * @param heartbeatTimeout 마지막 상태 조회 후 대기 요청을 유지하는 최대 시간
     * @param maxWaitDuration heartbeat가 유지되더라도 대기할 수 있는 최대 시간
     * @param quarantineTtl 손상된 요청 원문을 격리 Key에 보관하는 시간
     * @return 입장 허용, 수용량 초과, 빈 대기열 또는 재시도 결과
     */
    public WriteResult admitNext(
            String serviceId,
            int maxConcurrentUsers,
            Duration admissionTimeout,
            Duration heartbeatTimeout,
            Duration maxWaitDuration,
            Duration quarantineTtl
    ) {
        validateServiceId(serviceId);
        // ponytail: 후보 1건만 먼저 읽고 Lua가 선두와 수용량을 재검증합니다.
        // 경합으로 RETRY가 누적되면 Step 4의 제한된 batch Lua로 교체합니다.
        Set<String> first = redis.opsForZSet().range(waitingKey(serviceId), 0, 0);
        if (first == null || first.isEmpty()) {
            return new WriteResult(ResultType.EMPTY, null);
        }
        String reservationRequestId = first.iterator().next();

        return execute(
                ADMIT_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        waitingKey(serviceId),
                        heartbeatKey(serviceId),
                        activeSlotsKey(serviceId),
                        quarantineKey(serviceId, reservationRequestId, System.currentTimeMillis())
                ),
                reservationRequestId,
                Integer.toString(maxConcurrentUsers),
                Long.toString(admissionTimeout.toMillis()),
                Long.toString(heartbeatTimeout.toMillis()),
                Long.toString(maxWaitDuration.toMillis()),
                Long.toString(quarantineTtl.toMillis()),
                serviceId
        );
    }

    /**
     * 서비스 단위 유지보수 lease를 획득합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param instanceId lease 소유 Waiting Room 인스턴스 식별자
     * @param leaseTimeout 장애 시 lease가 자동 해제되는 최대 시간
     * @return 현재 인스턴스가 lease를 획득했으면 {@code true}
     */
    public boolean tryAcquireMaintenanceLease(String serviceId, String instanceId, Duration leaseTimeout) {
        validateServiceId(serviceId);
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                maintenanceLeaseKey(serviceId),
                instanceId,
                leaseTimeout
        ));
    }

    /**
     * 현재 인스턴스가 소유한 서비스 유지보수 lease만 해제합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param instanceId lease 소유 Waiting Room 인스턴스 식별자
     */
    public void releaseMaintenanceLease(String serviceId, String instanceId) {
        validateServiceId(serviceId);
        redis.execute(RELEASE_LEASE_SCRIPT, List.of(maintenanceLeaseKey(serviceId)), instanceId);
    }

    /**
     * 만료시각이 빠른 활성 슬롯 후보를 제한된 수만큼 조회합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param limit 최대 후보 수
     * @return 만료시각 오름차순의 요청 식별자
     */
    public List<String> activeExpirationCandidates(String serviceId, int limit) {
        validateServiceId(serviceId);
        return rangeCandidates(activeSlotsKey(serviceId), limit);
    }

    /**
     * 마지막 heartbeat가 오래된 대기 요청 후보를 제한된 수만큼 조회합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param limit 최대 후보 수
     * @return heartbeat 시각 오름차순의 요청 식별자
     */
    public List<String> heartbeatExpirationCandidates(String serviceId, int limit) {
        validateServiceId(serviceId);
        return rangeCandidates(heartbeatKey(serviceId), limit);
    }

    /**
     * 최초 대기시각이 오래된 요청 후보를 제한된 수만큼 조회합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param limit 최대 후보 수
     * @return 최초 대기시각 오름차순의 요청 식별자
     */
    public List<String> waitingExpirationCandidates(String serviceId, int limit) {
        validateServiceId(serviceId);
        return rangeCandidates(waitingKey(serviceId), limit);
    }

    /**
     * Redis 시각을 기준으로 만료된 활성 슬롯을 제거하고 요청을 EXPIRED로 변경합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param quarantineTtl 손상된 요청 원문을 격리 Key에 보관하는 시간
     * @param etaWindow 슬롯 반환 표본을 유지할 관측 구간
     * @return 만료 적용, 아직 유효함 또는 대상 없음 결과
     */
    public WriteResult expireActiveIfDue(
            String serviceId,
            String reservationRequestId,
            Duration quarantineTtl,
            Duration etaWindow
    ) {
        validateIdentifiers(serviceId, reservationRequestId);
        return execute(
                EXPIRE_ACTIVE_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        activeSlotsKey(serviceId),
                        quarantineKey(serviceId, reservationRequestId, System.currentTimeMillis()),
                        slotReleaseEventsKey(serviceId)
                ),
                reservationRequestId,
                slotReleasedChannel(serviceId),
                Long.toString(quarantineTtl.toMillis()),
                serviceId,
                Long.toString(etaWindow.toMillis())
        );
    }

    /**
     * Redis 시각을 기준으로 heartbeat 또는 최대 대기시간이 지난 요청을 만료합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param heartbeatTimeout 마지막 상태 조회 후 대기 요청을 유지하는 최대 시간
     * @param maxWaitDuration heartbeat가 유지되더라도 대기할 수 있는 최대 시간
     * @param quarantineTtl 손상된 요청 원문을 격리 Key에 보관하는 시간
     * @return 만료 적용, 아직 유효함 또는 대상 없음 결과
     */
    public WriteResult expireWaitingIfDue(
            String serviceId,
            String reservationRequestId,
            Duration heartbeatTimeout,
            Duration maxWaitDuration,
            Duration quarantineTtl
    ) {
        validateIdentifiers(serviceId, reservationRequestId);
        return execute(
                EXPIRE_WAITING_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        waitingKey(serviceId),
                        heartbeatKey(serviceId),
                        quarantineKey(serviceId, reservationRequestId, System.currentTimeMillis())
                ),
                reservationRequestId,
                Long.toString(maxWaitDuration.toMillis()),
                Long.toString(heartbeatTimeout.toMillis()),
                Long.toString(quarantineTtl.toMillis()),
                serviceId
        );
    }

    /**
     * 입장 허용 요청을 실제 입장 상태로 바꾸고 활성 슬롯 만료시간을 갱신합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param maxSessionDuration 실제 입장 후 활성 슬롯의 최대 유지시간
     * @param etaWindow 슬롯 반환 표본을 유지할 관측 구간
     * @return 상태 변경, 멱등 재호출 또는 상태 충돌 결과
     */
    public WriteResult enter(
            String serviceId,
            String reservationRequestId,
            Duration maxSessionDuration,
            Duration etaWindow
    ) {
        validateIdentifiers(serviceId, reservationRequestId);
        return execute(
                ENTER_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        activeSlotsKey(serviceId),
                        slotReleaseEventsKey(serviceId)
                ),
                reservationRequestId,
                Long.toString(maxSessionDuration.toMillis()),
                slotReleasedChannel(serviceId),
                Long.toString(etaWindow.toMillis())
        );
    }

    /**
     * 실제 입장한 요청의 완료시각을 기록하고 활성 슬롯을 반환합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param etaWindow 슬롯 반환 표본을 유지할 관측 구간
     * @return 최초 완료, 멱등 재호출 또는 상태 충돌 결과
     */
    public WriteResult complete(String serviceId, String reservationRequestId, Duration etaWindow) {
        validateIdentifiers(serviceId, reservationRequestId);
        return execute(
                COMPLETE_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        activeSlotsKey(serviceId),
                        slotReleaseEventsKey(serviceId)
                ),
                reservationRequestId,
                slotReleasedChannel(serviceId),
                Long.toString(etaWindow.toMillis())
        );
    }

    /**
     * 대기 중이거나 입장이 허용된 요청을 취소하고 관련 ZSET membership을 제거합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param etaWindow 슬롯 반환 표본을 유지할 관측 구간
     * @return 최초 취소, 멱등 재호출 또는 상태 충돌 결과
     */
    public WriteResult cancel(String serviceId, String reservationRequestId, Duration etaWindow) {
        validateIdentifiers(serviceId, reservationRequestId);
        return execute(
                CANCEL_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        waitingKey(serviceId),
                        heartbeatKey(serviceId),
                        activeSlotsKey(serviceId),
                        slotReleaseEventsKey(serviceId)
                ),
                reservationRequestId,
                slotReleasedChannel(serviceId),
                Long.toString(etaWindow.toMillis())
        );
    }

    /**
     * 요청 상태를 변경하지 않고 조회합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return 저장된 요청 상태이며 존재하지 않으면 {@code null}
     */
    public WaitingRequestState find(String serviceId, String reservationRequestId) {
        validateIdentifiers(serviceId, reservationRequestId);
        String encoded = redis.opsForValue().get(requestKey(serviceId, reservationRequestId));
        return encoded == null ? null : readJson(encoded);
    }

    /**
     * 상태를 조회하고 WAITING 요청의 heartbeat, 순번과 전체 대기 인원을 함께 갱신·반환합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @param shortPollDelay 예상 대기시간이 1분 미만일 때 사용할 상태조회 주기
     * @param mediumPollDelay 예상 대기시간이 1시간 미만일 때 사용할 상태조회 주기
     * @param longPollDelay 예상 대기시간이 1시간 이상이거나 계산 불가일 때 사용할 상태조회 주기
     * @param maxConcurrentUsers 대상 서비스가 허용하는 최대 활성 사용자 수
     * @param heartbeatTimeout 마지막 정상 조회 후 요청을 유지할 시간
     * @param maxRetryAfter 제한 응답으로 안내할 최대 대기시간
     * @param retryAfterSafetyMargin heartbeat 만료 전에 확보할 안전 여유시간
     * @param etaWindow 슬롯 반환률을 계산할 최근 관측 구간
     * @param etaMinSamples 슬롯 반환률 계산에 필요한 최소 표본 수
     * @param etaMinObservation 반환률 계산에 사용할 최소 관측시간
     * @param etaInitialReleaseRatePerSecond 실측 표본 부족 시 사용할 초당 초기 슬롯 반환률이며 0이면 비활성화
     * @param maxAdmissionsPerRun 한 스케줄러 실행에서 허용할 최대 입장 수
     * @param schedulerInterval 입장 스케줄러 실행 주기
     * @return 현재 요청 상태와 대기열 snapshot
     */
    public WaitingSnapshot poll(
            String serviceId,
            String reservationRequestId,
            Duration shortPollDelay,
            Duration mediumPollDelay,
            Duration longPollDelay,
            int maxConcurrentUsers,
            Duration heartbeatTimeout,
            Duration maxRetryAfter,
            Duration retryAfterSafetyMargin,
            Duration etaWindow,
            int etaMinSamples,
            Duration etaMinObservation,
            double etaInitialReleaseRatePerSecond,
            int maxAdmissionsPerRun,
            Duration schedulerInterval
    ) {
        validateIdentifiers(serviceId, reservationRequestId);
        List<?> response = redis.execute(
                POLL_SCRIPT,
                List.of(
                        requestKey(serviceId, reservationRequestId),
                        waitingKey(serviceId),
                        heartbeatKey(serviceId),
                        activeSlotsKey(serviceId),
                        slotReleaseEventsKey(serviceId)
                ),
                reservationRequestId,
                serviceId,
                Long.toString(shortPollDelay.toMillis()),
                Long.toString(mediumPollDelay.toMillis()),
                Long.toString(longPollDelay.toMillis()),
                Integer.toString(maxConcurrentUsers),
                Long.toString(heartbeatTimeout.toMillis()),
                Long.toString(maxRetryAfter.toMillis()),
                Long.toString(retryAfterSafetyMargin.toMillis()),
                Long.toString(etaWindow.toMillis()),
                Integer.toString(etaMinSamples),
                Long.toString(etaMinObservation.toMillis()),
                Double.toString(etaInitialReleaseRatePerSecond),
                Integer.toString(maxAdmissionsPerRun),
                Long.toString(schedulerInterval.toMillis())
        );
        if (response == null || response.size() < 6) {
            throw new IllegalStateException("Redis Polling Lua script가 올바른 결과를 반환하지 않았습니다.");
        }

        ResultType type = ResultType.valueOf(stringValue(response.getFirst()));
        String encoded = stringValue(response.get(1));
        String position = stringValue(response.get(2));
        String waitingCount = stringValue(response.get(3));
        String delay = stringValue(response.get(4));
        String estimatedWaitSeconds = stringValue(response.get(5));
        return new WaitingSnapshot(
                type,
                encoded.isEmpty() ? null : readJson(encoded),
                position.isEmpty() ? null : Long.valueOf(position),
                waitingCount.isEmpty() ? 0 : Long.parseLong(waitingCount),
                delay.isEmpty() ? null : Long.valueOf(delay),
                estimatedWaitSeconds.isEmpty() ? null : Long.valueOf(estimatedWaitSeconds)
        );
    }

    private WriteResult execute(DefaultRedisScript<List> script, List<String> keys, String... arguments) {
        List<?> response = redis.execute(script, keys, (Object[]) arguments);
        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Redis Lua script가 결과를 반환하지 않았습니다.");
        }

        ResultType type = ResultType.valueOf(stringValue(response.getFirst()));
        WaitingRequestState state = null;
        if (response.size() > 1) {
            String encoded = stringValue(response.get(1));
            if (!encoded.isEmpty()) {
                state = readJson(encoded);
            }
        }
        return new WriteResult(type, state);
    }

    private String writeJson(WaitingRequestState state) {
        return objectMapper.writeValueAsString(state);
    }

    private WaitingRequestState readJson(String encoded) {
        return objectMapper.readValue(encoded, WaitingRequestState.class);
    }

    private List<String> rangeCandidates(String key, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> candidates = redis.opsForZSet().range(key, 0, limit - 1L);
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    private static String stringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return Objects.toString(value, "");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DefaultRedisScript<List> script(String source) {
        return new DefaultRedisScript(source, List.class);
    }

    private static void validateIdentifiers(String serviceId, String reservationRequestId) {
        validateServiceId(serviceId);
        if (reservationRequestId == null || reservationRequestId.isBlank()) {
            throw new IllegalArgumentException("reservationRequestId는 비어 있을 수 없습니다.");
        }
    }

    private static void validateServiceId(String serviceId) {
        if (serviceId == null || !serviceId.matches(SERVICE_ID_PATTERN)) {
            throw new IllegalArgumentException("serviceId는 영문 소문자, 숫자와 '-'만 사용할 수 있습니다.");
        }
    }

    private static String waitingKey(String serviceId) {
        return "waiting:{" + serviceId + "}";
    }

    private static String heartbeatKey(String serviceId) {
        return "waiting-heartbeat:{" + serviceId + "}";
    }

    private static String activeSlotsKey(String serviceId) {
        return "active-slots:{" + serviceId + "}";
    }

    private static String slotReleaseEventsKey(String serviceId) {
        return "slot-release-events:{" + serviceId + "}";
    }

    private static String requestKey(String serviceId, String reservationRequestId) {
        return "waiting-request:{" + serviceId + "}:" + reservationRequestId;
    }

    private static String maintenanceLeaseKey(String serviceId) {
        return "maintenance-lease:{" + serviceId + "}";
    }

    private static String quarantineKey(String serviceId, String reservationRequestId, long detectedAt) {
        return "waiting-quarantine:{" + serviceId + "}:" + reservationRequestId + ":" + detectedAt;
    }

    private static String slotReleasedChannel(String serviceId) {
        return "waiting-room:slot-released:{" + serviceId + "}";
    }

    /** Redis 원자 처리의 업무 결과입니다. */
    public enum ResultType {
        /** 대기 요청이 새로 등록됐습니다. */
        CREATED,

        /** 상태와 관련 Sorted Set 변경이 적용됐습니다. */
        APPLIED,

        /** 요청 상태와 일치하지 않는 고아 Sorted Set membership을 제거했습니다. */
        CLEANED,

        /** 같은 요청이 이미 처리되어 기존 결과를 반환했습니다. */
        REPLAYED,

        /** 현재 상태 또는 payload가 요청 작업과 충돌합니다. */
        CONFLICT,

        /** 입장 허용시간이 지나 요청과 활성 슬롯을 만료했습니다. */
        ADMISSION_EXPIRED,

        /** 활성 슬롯이 설정된 최대 수용량에 도달했습니다. */
        FULL,

        /** 처리할 대기 요청이 없습니다. */
        EMPTY,

        /** 다른 실행이 먼저 상태를 바꿔 다시 조회해야 합니다. */
        RETRY,

        /** Redis Key type 또는 저장 JSON이 기대한 구조와 다릅니다. */
        STORE_ERROR,

        /** 상태조회 대상 요청을 찾았습니다. */
        FOUND,

        /** 서버가 안내한 허용 시각보다 상태조회를 일찍 요청했습니다. */
        RATE_LIMITED,

        /** 상태조회 대상 요청이 존재하지 않습니다. */
        NOT_FOUND
    }

    /**
     * Lua 처리 결과와 변경 후 요청 상태입니다.
     *
     * @param type 처리 결과 유형
     * @param state 처리 후 상태이며 상태가 없는 결과에서는 {@code null}
     */
    public record WriteResult(ResultType type, WaitingRequestState state) {
    }

    /**
     * 상태조회 시점의 요청 상태와 대기열 정보입니다.
     *
     * @param type 조회 결과 유형
     * @param state 저장된 요청 상태
     * @param position 1부터 시작하는 현재 대기 순번
     * @param waitingCount 해당 서비스의 전체 대기 인원
     * @param delayMs 정상 응답의 다음 조회 주기 또는 제한 응답의 남은 대기시간
     * @param estimatedWaitSeconds 입장 허용까지의 예상 대기시간이며 계산 근거가 없으면 {@code null}
     */
    public record WaitingSnapshot(
            ResultType type,
            WaitingRequestState state,
            Long position,
            long waitingCount,
            Long delayMs,
            Long estimatedWaitSeconds
    ) {
    }
}
