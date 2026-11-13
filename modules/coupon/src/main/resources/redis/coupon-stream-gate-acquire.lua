local gateKind = redis.call('TYPE', KEYS[1]).ok
local activeKind = redis.call('TYPE', KEYS[2]).ok
if gateKind ~= 'hash' then return -1 end
if activeKind ~= 'none' and activeKind ~= 'set' then return -3 end
if redis.call('HGET', KEYS[1], 'state') ~= 'OPEN' then return -1 end
if redis.call('HGET', KEYS[1], 'capacity') ~= ARGV[1] then
    redis.call('HSET', KEYS[1], 'state', 'CLOSED', 'reason', 'CAPACITY_MISMATCH')
    return -3
end
for i = 3, #ARGV do
    if redis.call('SISMEMBER', KEYS[2], ARGV[i]) == 0 then
        redis.call('HSET', KEYS[1], 'state', 'CLOSED', 'reason', 'TOKEN_LOST')
        return -3
    end
end
if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[1]) then return 0 end
redis.call('SADD', KEYS[2], ARGV[2])
return 1
