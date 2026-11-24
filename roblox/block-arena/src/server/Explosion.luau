-- 폭발 (TNT, 화염구): 주변 블록 파괴 + 피해 + 넉백 (몹 포함)
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Config = require(ReplicatedStorage.Shared.Config)
local Session = require(script.Parent.Session)
local Combat = require(script.Parent.Combat)

local B = Config.BlockSize

local Explosion = {}

export type Options = {
	world: any,
	players: { Player },
	canBreak: (record: any) -> boolean,
	canHurt: (source: Player?, victim: Player) -> boolean,
	source: Player?,
	radius: number, -- 블록 단위
	power: number, -- 가운데에서 받는 최대 피해
	match: any?,
}

-- 몹(골렘, 좀벌레, 용)도 폭발에 다치게 Mobs 가 채워 넣음
Explosion.hurtMobs = function(_match: any, _position: Vector3, _reach: number, _power: number, _source: Player?) end

function Explosion.explode(position: Vector3, opts: Options)
	local effect = Instance.new("Explosion")
	effect.Position = position
	effect.BlastPressure = 0
	effect.BlastRadius = opts.radius * B
	effect.DestroyJointRadiusPercent = 0
	effect.ExplosionType = Enum.ExplosionType.NoCraters
	effect.Parent = workspace

	local world = opts.world
	if world then
		local cx, cy, cz = world:toCell(position)
		local r = math.ceil(opts.radius)
		for x = cx - r, cx + r do
			for y = cy - r, cy + r do
				for z = cz - r, cz + r do
					local dx, dy, dz = x - cx, y - cy, z - cz
					if dx * dx + dy * dy + dz * dz <= opts.radius * opts.radius then
						local record = world:get(x, y, z)
						if record and opts.canBreak(record) then
							world:remove(x, y, z)
						end
					end
				end
			end
		end
	end

	local reach = opts.radius * B * 1.4
	if opts.match then
		Explosion.hurtMobs(opts.match, position, reach, opts.power, opts.source)
	end
	for _, player in opts.players do
		local state = Session.get(player)
		local root = state and state.root
		if state and state.alive and root then
			local offset = root.Position - position
			local distance = offset.Magnitude
			if distance <= reach then
				local falloff = 1 - distance / reach
				local dir = if distance > 0.1 then offset.Unit else Vector3.yAxis
				local knock = dir * (40 * falloff + 14) + Vector3.new(0, 22 * falloff + 10, 0)
				if opts.canHurt(opts.source, player) then
					local damage = opts.power * falloff
					if player == opts.source then
						damage *= 0.35 -- 화염구 점프용으로 자기 피해는 줄여 줌
					end
					Combat.damage(player, math.max(0.5, damage), { attacker = opts.source, cause = "explosion", knockback = knock })
				else
					Combat.knockback(player, knock)
				end
			end
		end
	end
end

return Explosion
