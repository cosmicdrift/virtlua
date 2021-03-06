testCall(function()
	local status, a, b, c, d = pcall(function(x, y, z) return x*y*z, x + y, z end, 5, 6, 7)
	assert(status == true)
	assert(a == 5 * 6 * 7)
	assert(b == 5 + 6)
	assert(c == 7)
	assert(d == nil)
end)

testCall(function()
	local status, a, b, c, d = pcall(function(x, y, z) return x*y*z, x + y, z end, 5, nil, 7)
	assert(status == false)
	assert(type(a) == "string")
	assert(type(b) == "string")
	assert(type(c) == "userdata")
end)

