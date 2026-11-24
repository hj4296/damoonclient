-- 작은 도우미 함수들 (시간 표시, 고정 시드 난수)
local Util = {}

function Util.formatTime(seconds: number): string
	seconds = math.max(0, math.floor(seconds))
	return string.format("%d:%02d", seconds // 60, seconds % 60)
end

-- 맵 생성을 매번 똑같이 만들기 위한 선형 합동 난수
local Rng = {}
Rng.__index = Rng

export type Rng = typeof(setmetatable({} :: { state: number }, Rng))

function Util.rng(seed: number): Rng
	local self = setmetatable({ state = math.floor(seed) % 2147483647 }, Rng)
	if self.state <= 0 then
		self.state += 2147483646
	end
	return self
end

function Rng.next(self: Rng): number
	self.state = (self.state * 48271) % 2147483647
	return self.state / 2147483647
end

function Rng.int(self: Rng, a: number, b: number): number
	return a + math.floor(self:next() * (b - a + 1))
end

function Rng.chance(self: Rng, p: number): boolean
	return self:next() < p
end

function Rng.pick<T>(self: Rng, list: { T }): T
	return list[self:int(1, #list)]
end

function Rng.shuffle<T>(self: Rng, list: { T }): { T }
	for i = #list, 2, -1 do
		local j = self:int(1, i)
		list[i], list[j] = list[j], list[i]
	end
	return list
end

return Util
