-- 경기장 안의 폭발 (TNT, 화염구, 럭키 블록 함정). 모드 규칙에 따라 블록을 부수고,
-- 폭발 지점 가까이에 물이 있으면 블록은 부서지지 않습니다(마인크래프트처럼 물속 TNT).
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Blocks = require(ReplicatedStorage.Shared.Blocks)
local Session = require(script.Parent.Session)
local Combat = require(script.Parent.Combat)
local Explosion = require(script.Parent.Explosion)

local Blast = {}

local function inGame(match): { Player }
	local out = {}
	for _, player in match.players do
		local state = Session.get(player)
		if state and state.location == "match" and state.match == match then
			table.insert(out, player)
		end
	end
	return out
end

-- 폭발 지점 주변 한 칸 안에 물이 있는지
function Blast.nearWater(world, position: Vector3): boolean
	local cx, cy, cz = world:toCell(position)
	for x = cx - 1, cx + 1 do
		for y = cy - 1, cy + 1 do
			for z = cz - 1, cz + 1 do
				local record = world:get(x, y, z)
				local def = if record then Blocks.get(record.id) else nil
				if def and def.water then
					return true
				end
			end
		end
	end
	return false
end

function Blast.explode(match, position: Vector3, radius: number, power: number, source: Player?)
	local wet = Blast.nearWater(match.world, position)
	Explosion.explode(position, {
		world = match.world,
		players = inGame(match),
		canBreak = function(record)
			return not wet and match.mode.canExplode(match, record)
		end,
		canHurt = function(src: Player?, victim: Player): boolean
			if not src or src == victim then
				return true
			end
			return Combat.canDamage(src, victim)
		end,
		source = source,
		radius = radius,
		power = power,
		match = match,
	})
end

-- 놓인 TNT: 3초 동안 깜빡이다 터짐
function Blast.prime(match, record, source: Player?, fuse: number?)
	task.spawn(function()
		local part = record.part
		if not part then
			return
		end
		local steps = math.max(2, math.floor((fuse or 3) / 0.25))
		for i = 1, steps do
			task.wait(0.25)
			if match.world:get(record.x, record.y, record.z) ~= record or match.state ~= "playing" then
				return
			end
			part.Color = if i % 2 == 0 then Color3.fromRGB(214, 52, 40) else Color3.fromRGB(255, 255, 255)
		end
		if match.world:get(record.x, record.y, record.z) ~= record then
			return
		end
		local position = part.Position
		match.world:remove(record.x, record.y, record.z)
		Blast.explode(match, position, 3.2, 8, source)
	end)
end

return Blast
