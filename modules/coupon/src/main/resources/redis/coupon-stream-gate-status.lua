local function kind(key) return redis.call('TYPE', key).ok end
if kind(KEYS[1]) ~= 'none' and kind(KEYS[1]) ~= 'hash' then return redis.error_reply('invalid gate type') end
if kind(KEYS[2]) ~= 'none' and kind(KEYS[2]) ~= 'set' then return redis.error_reply('invalid permit type') end
local result = {redis.call('HGET', KEYS[1], 'state') or 'CLOSED', redis.call('HGET', KEYS[1], 'epoch') or '', redis.call('HGET', KEYS[1], 'reason') or 'MISSING_STATE'}
for _, token in ipairs(redis.call('SMEMBERS', KEYS[2])) do result[#result + 1] = token end
return result
