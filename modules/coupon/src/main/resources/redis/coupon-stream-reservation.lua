local function kind(key) return redis.call('TYPE', key).ok end
local expected = {'string', 'set', 'hash', 'stream'}
for i = 1, 4 do
    local actual = kind(KEYS[i])
    if actual ~= 'none' and actual ~= expected[i] then return 'STATE_LOST' end
end
if not tonumber(redis.call('GET', KEYS[1])) then return 'STATE_LOST' end
if kind(KEYS[4]) ~= 'stream' then return 'STATE_LOST' end
if redis.call('SCARD', KEYS[2]) ~= redis.call('HLEN', KEYS[3]) then return 'STATE_LOST' end
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 0 then return 'NONE' end
local id = redis.call('HGET', KEYS[3], ARGV[2])
if not id then return 'STATE_LOST' end
if id ~= 'issued' and #redis.call('XRANGE', KEYS[4], id, id) ~= 1 then return 'STATE_LOST' end
return 'EXISTING|' .. id
