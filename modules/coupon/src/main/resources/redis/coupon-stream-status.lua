local function kind(key) return redis.call('TYPE', key).ok end
local expected = {'string', 'set', 'hash', 'stream'}
for i = 1, 4 do
    local actual = kind(KEYS[i])
    if actual ~= 'none' and actual ~= expected[i] then return 'STATE_LOST' end
end
local quota = tonumber(redis.call('GET', KEYS[1]))
if not quota then return 'STATE_LOST' end
if kind(KEYS[4]) ~= 'stream' then return 'STATE_LOST' end
local members = redis.call('SCARD', KEYS[2])
if members ~= redis.call('HLEN', KEYS[3]) or members > quota then return 'STATE_LOST' end
local jobs = 0
for _, member in ipairs(redis.call('SMEMBERS', KEYS[2])) do
    local id = redis.call('HGET', KEYS[3], member)
    if not id then return 'STATE_LOST' end
    if id ~= 'issued' then
        jobs = jobs + 1
        if #redis.call('XRANGE', KEYS[4], id, id) ~= 1 then return 'STATE_LOST' end
    end
end
if jobs ~= redis.call('XLEN', KEYS[4]) then return 'STATE_LOST' end
return quota .. '|' .. members .. '|' .. redis.call('XLEN', KEYS[4])
