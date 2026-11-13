local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('invalid gate type') end
redis.call('HSETNX', KEYS[1], 'epoch', ARGV[2])
redis.call('HSET', KEYS[1], 'state', 'CLOSED', 'reason', ARGV[1])
return 1
