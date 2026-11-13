local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'hash' then return -1 end
kind = redis.call('TYPE', KEYS[2]).ok
if kind ~= 'none' and kind ~= 'set' then return -1 end
if redis.call('HGET', KEYS[1], 'state') == 'OPEN' then return -1 end
local tokens = redis.call('SMEMBERS', KEYS[2])
if #tokens ~= #ARGV - 2 then return -1 end
local acknowledged = {}
for i = 3, #ARGV do acknowledged[ARGV[i]] = true end
for _, token in ipairs(tokens) do if not acknowledged[token] then return -1 end end
redis.call('DEL', KEYS[2])
redis.call('HSET', KEYS[1], 'state', 'OPEN', 'epoch', ARGV[1], 'capacity', ARGV[2], 'reason', '')
return 1
