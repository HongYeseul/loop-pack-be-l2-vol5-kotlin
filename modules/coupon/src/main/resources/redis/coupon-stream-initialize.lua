local quota = tonumber(ARGV[1])
if not quota or quota < 0 or quota % 1 ~= 0 then return 'CONFLICT' end
local function kind(key) return redis.call('TYPE', key).ok end
local expected = {'string', 'set', 'hash', 'stream'}
for i = 1, 4 do
    local actual = kind(KEYS[i])
    if actual ~= 'none' and actual ~= expected[i] then return 'CONFLICT' end
end
if redis.call('EXISTS', KEYS[1]) == 1 then
    if tonumber(redis.call('GET', KEYS[1])) ~= quota then return 'CONFLICT' end
    local count = redis.call('SCARD', KEYS[2])
    if count > quota or count ~= redis.call('HLEN', KEYS[3]) then return 'CONFLICT' end
    local jobs = 0
    for _, member in ipairs(redis.call('SMEMBERS', KEYS[2])) do
        local id = redis.call('HGET', KEYS[3], member)
        if not id then return 'CONFLICT' end
        if id ~= 'issued' then
            jobs = jobs + 1
            if kind(KEYS[4]) ~= 'stream' or #redis.call('XRANGE', KEYS[4], id, id) ~= 1 then return 'CONFLICT' end
        end
    end
    if jobs ~= redis.call('XLEN', KEYS[4]) then return 'CONFLICT' end
    return 'OK'
end
for i = 2, 4 do if redis.call('EXISTS', KEYS[i]) == 1 then return 'CONFLICT' end end
local unique = {}
for i = 2, #ARGV do unique[ARGV[i]] = true end
local count = 0
for _ in pairs(unique) do count = count + 1 end
if count > quota then return 'CONFLICT' end
redis.call('SET', KEYS[1], ARGV[1])
for user in pairs(unique) do
    redis.call('SADD', KEYS[2], user)
    redis.call('HSET', KEYS[3], user, 'issued')
end
local initial = redis.call('XADD', KEYS[4], '*', '_initialize', '1')
redis.call('XDEL', KEYS[4], initial)
return 'OK'
