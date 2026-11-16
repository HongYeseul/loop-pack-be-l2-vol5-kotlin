local function kind(key) return redis.call('TYPE', key).ok end
if kind(KEYS[1]) ~= 'hash' then return 'CLOSED' end
if redis.call('HGET', KEYS[1], 'state') ~= 'OPEN' or redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[3] then return 'CLOSED' end
local expected = {'hash', 'string', 'set', 'hash', 'stream'}
for i = 2, 5 do
    local actual = kind(KEYS[i])
    if actual ~= 'none' and actual ~= expected[i] then return 'STATE_LOST' end
end
local quota = tonumber(redis.call('GET', KEYS[2]))
if not quota or quota < 0 then return 'STATE_LOST' end
if kind(KEYS[5]) ~= 'stream' then return 'STATE_LOST' end
if redis.call('SCARD', KEYS[3]) ~= redis.call('HLEN', KEYS[4]) then return 'STATE_LOST' end
if redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 1 then
    local id = redis.call('HGET', KEYS[4], ARGV[2])
    if not id then return 'STATE_LOST' end
    if id ~= 'issued' and #redis.call('XRANGE', KEYS[5], id, id) ~= 1 then return 'STATE_LOST' end
    return 'EXISTING|' .. id
end
if redis.call('SCARD', KEYS[3]) >= quota then return 'FULL' end
local id = redis.call('XADD', KEYS[5], '*', 'campaignId', ARGV[1], 'userId', ARGV[2])
redis.call('SADD', KEYS[3], ARGV[2])
redis.call('HSET', KEYS[4], ARGV[2], id)
return 'NEW|' .. id
