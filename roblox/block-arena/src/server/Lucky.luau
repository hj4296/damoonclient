-- 럭키 블록: 부수면 결과표(LuckyTable)에서 하나를 뽑아 좋은 일이나 나쁜 일이 일어납니다.
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)
local Combat = require(script.Parent.Combat)
local Status = require(script.Parent.Status)
local Drops = require(script.Parent.Drops)
local Blast = require(script.Parent.Blast)
local Mobs = require(script.Parent.Mobs)
local LuckyTable = require(script.Parent.Logic.LuckyTable)

local Lucky = {}

local random = Random.new()
local function rand(): number
	return random:NextNumber()
end

local function canPickup(match)
	return function(other: Player): boolean
		local s = Session.get(other)
		return s ~= nil and s.match == match and s.location == "match"
	end
end

local function lightning(match, position: Vector3)
	local bolt = Instance.new("Part")
	bolt.Name = "Lightning"
	bolt.Anchored = true
	bolt.CanCollide = false
	bolt.CanQuery = false
	bolt.CanTouch = false
	bolt.Material = Enum.Material.Neon
	bolt.Color = Color3.fromRGB(220, 230, 255)
	bolt.Size = Vector3.new(0.6, 80, 0.6)
	bolt.CFrame = CFrame.new(position + Vector3.new(0, 40, 0))
	bolt.Parent = match.world.extras
	task.delay(0.25, function()
		bolt:Destroy()
	end)
end

function Lucky.open(match, player: Player, x: number, y: number, z: number)
	local world = match.world
	local center = world:cellCenter(x, y, z)
	local outcome = LuckyTable.pick(rand)
	local color = if outcome.good then "#55FF55" else "#FF5555"
	Net.send(player, "chat", string.format('<font color="#FFD24A">럭키 블록</font> > <font color="%s">%s</font>', color, outcome.message))
	Net.send(player, "sound", if outcome.good then "buy" else "deny")
	local state = Session.get(player)

	if outcome.kind == "items" and outcome.items then
		local stacks = {}
		for _, item in outcome.items do
			table.insert(stacks, { id = item.id, count = item.count })
		end
		Drops.spawn(world.extras, center, stacks, canPickup(match))
	elseif outcome.kind == "tnt" then
		for i = 1, outcome.count or 1 do
			local dx = (i - 2)
			local record = world:get(x + dx, y, z)
			if not record then
				local tnt = world:set(x + dx, y, z, "tnt", true)
				if tnt then
					Blast.prime(match, tnt, nil, 2)
				end
			end
		end
	elseif outcome.kind == "mobs" then
		for i = 1, outcome.count or 1 do
			local angle = i * math.pi * 2 / (outcome.count or 1)
			local position = center - Vector3.new(0, 1.4, 0) + Vector3.new(math.cos(angle), 0, math.sin(angle)) * 1.5
			Mobs.spawn(match, "silverfish", position, { life = 25 })
		end
	elseif outcome.kind == "effect" and outcome.effects then
		for _, effect in outcome.effects do
			Status.add(player, effect.name, effect.duration)
		end
	elseif outcome.kind == "launch" then
		Combat.knockback(player, Vector3.new(0, 115, 0))
	elseif outcome.kind == "lightning" and state and state.root then
		lightning(match, state.root.Position)
		Combat.damage(player, 5, { cause = "lava", ignoreArmor = true })
		Status.add(player, "fire", 3)
	end
end

return Lucky
