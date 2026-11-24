-- 클라이언트 요청 처리: 블록 설치/부수기, 공격, 아이템 사용, 가방 정리, 상점, 투표
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Items = require(Shared.Items)
local Blocks = require(Shared.Blocks)
local Rules = require(Shared.Rules)
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)
local Combat = require(script.Parent.Combat)
local World = require(script.Parent.World)
local Inventory = require(script.Parent.Logic.Inventory)
local Matches = require(script.Parent.Matches)
local Containers = require(script.Parent.Containers)
local Projectiles = require(script.Parent.Projectiles)
local Explosion = require(script.Parent.Explosion)
local Drops = require(script.Parent.Drops)

local B = Config.BlockSize

local Actions = {}

local NEIGHBORS = {
	{ 1, 0, 0 },
	{ -1, 0, 0 },
	{ 0, 1, 0 },
	{ 0, -1, 0 },
	{ 0, 0, 1 },
	{ 0, 0, -1 },
}

local function isInt(...: any): boolean
	for i = 1, select("#", ...) do
		local v = select(i, ...)
		if type(v) ~= "number" or v ~= v or math.floor(v) ~= v or math.abs(v) > 1000 then
			return false
		end
	end
	return true
end

-- 게임 중이고 살아 있는 사람만 통과
local function playing(player: Player)
	local state = Session.get(player)
	local match = state and state.match
	if not state or not match or match.state ~= "playing" or state.location ~= "match" then
		return nil, nil
	end
	if not state.alive or state.waitingRespawn or not state.root then
		return nil, nil
	end
	return state, match
end

local function explode(match, position: Vector3, radius: number, power: number, source: Player?)
	Explosion.explode(position, {
		world = match.world,
		players = Matches.inGame(match),
		canBreak = function(record)
			return match.mode.canExplode(match, record)
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
	})
end

local function primeTnt(match, record, source: Player)
	task.spawn(function()
		local part = record.part
		for i = 1, 12 do
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
		explode(match, position, 3.2, 8, source)
	end)
end

local function blockedByCharacter(match, center: Vector3): boolean
	local half = B / 2
	for _, other in match.players do
		local s = Session.get(other)
		local root = s and s.root
		if s and s.alive and root and root.Parent then
			local p = root.Position
			if math.abs(p.X - center.X) < half + 0.9 and math.abs(p.Z - center.Z) < half + 0.9 then
				local feet = p.Y - 2.9
				local head = p.Y + 2.2
				if feet < center.Y + half - 0.1 and head > center.Y - half then
					return true
				end
			end
		end
	end
	return false
end

Net.on("select", function(player, index)
	local state = Session.get(player)
	if state and isInt(index) and index >= 1 and index <= Inventory.HotbarSize then
		state.inv.selected = index
		state.breaking = nil
		Session.refreshHeld(state)
	end
end)

Net.on("place", function(player, x, y, z)
	if not isInt(x, y, z) then
		return
	end
	local state, match = playing(player)
	if not state or not match then
		return
	end
	local stack, index = Inventory.selectedStack(state.inv)
	local def = if stack then Items.get(stack.id) else nil
	if not stack or not def or def.kind ~= "block" or not def.block then
		return
	end
	local world = match.world
	if world:get(x, y, z) then
		return
	end
	local center = world:cellCenter(x, y, z)
	if (center - (state.root :: BasePart).Position).Magnitude > Config.Reach.BlockServer then
		return
	end
	local touching = false
	for _, n in NEIGHBORS do
		if world:get(x + n[1], y + n[2], z + n[3]) then
			touching = true
			break
		end
	end
	if not touching or blockedByCharacter(match, center) then
		return
	end
	if not match.mode.canPlace(match, player, x, y, z) then
		return
	end
	Inventory.consumeSlot(state.inv, index, 1)
	local record = world:set(x, y, z, def.block, true)
	local blockDef = Blocks.get(def.block)
	if record and blockDef and blockDef.tnt then
		primeTnt(match, record, player)
	end
end)

Net.on("breakStart", function(player, x, y, z)
	local state = Session.get(player)
	if state and isInt(x, y, z) then
		state.breaking = { key = World.key(x, y, z), time = os.clock() }
	end
end)

Net.on("break", function(player, x, y, z)
	if not isInt(x, y, z) then
		return
	end
	local state, match = playing(player)
	if not state or not match then
		return
	end
	local world = match.world
	local record = world:get(x, y, z)
	if not record then
		return
	end
	if (world:cellCenter(x, y, z) - (state.root :: BasePart).Position).Magnitude > Config.Reach.BlockServer then
		return
	end
	if not match.mode.canBreak(match, player, record) then
		return
	end
	local stack = state.inv.slots[state.inv.selected]
	local need = Rules.breakTime(record.id, if stack then stack.id else nil)
	local breaking = state.breaking
	if not need or not breaking or breaking.key ~= World.key(x, y, z) then
		return
	end
	if os.clock() - breaking.time < need * 0.6 - 0.15 then
		return
	end
	state.breaking = nil
	world:remove(x, y, z)
	if match.mode.onBreak then
		match.mode.onBreak(match, player, record)
	end
end)

Net.on("attack", function(player, target)
	if typeof(target) == "Instance" and target:IsA("Player") then
		Combat.melee(player, target)
	end
end)

local function launchArrow(player: Player, origin: Vector3, dir: Vector3, charge: number)
	local damage = 1 + 5 * charge
	if charge >= 0.99 then
		damage += 1
	end
	Projectiles.launch({
		shooter = player,
		origin = origin,
		velocity = dir * (60 + 120 * charge),
		gravity = 70,
		size = Vector3.new(0.2, 0.2, 2),
		color = Color3.fromRGB(150, 110, 70),
		onHitPlayer = function(victim, _pos, velocity)
			if Combat.canDamage(player, victim) then
				local push = Vector3.new(velocity.X, 0, velocity.Z)
				local knock = (if push.Magnitude > 0.1 then push.Unit else Vector3.zero) * 14 + Vector3.new(0, 10, 0)
				Combat.damage(victim, damage, { attacker = player, cause = "arrow", knockback = knock })
				Net.send(player, "sound", "arrowHit")
			end
		end,
		onHitWorld = function() end,
	})
end

local function throw(player: Player, match, kind: string, origin: Vector3, dir: Vector3)
	if kind == "snowball" then
		Projectiles.launch({
			shooter = player,
			origin = origin,
			velocity = dir * 90,
			gravity = 80,
			size = Vector3.new(0.7, 0.7, 0.7),
			color = Color3.fromRGB(245, 250, 255),
			shape = Enum.PartType.Ball,
			onHitPlayer = function(victim, _pos, velocity)
				if Combat.canDamage(player, victim) then
					local push = Vector3.new(velocity.X, 0, velocity.Z)
					local knock = (if push.Magnitude > 0.1 then push.Unit else Vector3.zero) * 20 + Vector3.new(0, 14, 0)
					Combat.damage(victim, 0, { attacker = player, cause = "melee", knockback = knock })
				end
			end,
			onHitWorld = function() end,
		})
	elseif kind == "pearl" then
		local function teleport(position: Vector3)
			local state = Session.get(player)
			if state and state.alive and state.match == match and match.state == "playing" and state.location == "match" then
				Session.teleport(player, CFrame.new(position + Vector3.new(0, 3.5, 0)))
				Combat.damage(player, 2, { cause = "fall", ignoreArmor = true })
			end
		end
		Projectiles.launch({
			shooter = player,
			origin = origin,
			velocity = dir * 75,
			gravity = 60,
			size = Vector3.new(0.7, 0.7, 0.7),
			color = Color3.fromRGB(40, 150, 130),
			shape = Enum.PartType.Ball,
			material = Enum.Material.Neon,
			onHitPlayer = function(_victim, position)
				teleport(position)
			end,
			onHitWorld = function(position, normal)
				teleport(position + normal * 1.5)
			end,
		})
	elseif kind == "fireball" then
		Projectiles.launch({
			shooter = player,
			origin = origin,
			velocity = dir * 45,
			gravity = 0,
			lifetime = 4,
			size = Vector3.new(1.6, 1.6, 1.6),
			color = Color3.fromRGB(255, 120, 30),
			shape = Enum.PartType.Ball,
			material = Enum.Material.Neon,
			light = true,
			onHitPlayer = function(_victim, position)
				explode(match, position, 2.6, 5, player)
			end,
			onHitWorld = function(position)
				explode(match, position, 2.6, 5, player)
			end,
		})
	end
end

Net.on("use", function(player, dir, charge)
	local state = Session.get(player)
	if not state then
		return
	end
	local stack, index = Inventory.selectedStack(state.inv)
	local def = if stack then Items.get(stack.id) else nil
	if not stack or not def then
		return
	end
	if def.kind == "menu" then
		if def.action == "games" then
			Net.send(player, "openGames", Matches.lobbyInfo())
		elseif def.action == "vote" and state.match then
			Matches.sendVote(state.match)
			Net.send(player, "openVote")
		elseif def.action == "leave" then
			Matches.leave(player)
		end
		return
	end

	local _, match = playing(player)
	if not match then
		return
	end
	local now = os.clock()
	if now - state.lastUse < 0.2 then
		return
	end
	state.lastUse = now

	if def.kind == "armor" then
		if Inventory.equipFromSlot(state.inv, index) then
			Net.send(player, "sound", "equip")
		end
	elseif def.kind == "food" then
		Inventory.consumeSlot(state.inv, index, 1)
		Combat.heal(player, 4)
		Combat.setAbsorption(player, 4, 120)
		Net.send(player, "sound", "eat")
	elseif def.kind == "bow" or def.kind == "throwable" then
		if typeof(dir) ~= "Vector3" or dir.Magnitude < 0.5 or dir.Magnitude > 2 then
			return
		end
		local unit = dir.Unit
		local origin = (state.root :: BasePart).Position + Vector3.new(0, 1.5, 0) + unit * 2.5
		if def.kind == "bow" then
			if not Inventory.remove(state.inv, "arrow", 1) then
				Session.chat({ player }, '<font color="#FF5555">화살이 없습니다!</font>')
				return
			end
			local power = math.clamp(tonumber(charge) or 1, 0.1, 1)
			launchArrow(player, origin, unit, power)
		elseif def.projectile then
			Inventory.consumeSlot(state.inv, index, 1)
			throw(player, match, def.projectile, origin, unit)
		end
	end
end)

Net.on("drop", function(player)
	local state, match = playing(player)
	if not state or not match then
		return
	end
	local stack, index = Inventory.selectedStack(state.inv)
	local def = if stack then Items.get(stack.id) else nil
	if not stack or not def or def.kind == "menu" then
		return
	end
	if match.modeId == "bedwars" and def.kind == "sword" and stack.id == "wood_sword" then
		return
	end
	local id = stack.id
	Inventory.consumeSlot(state.inv, index, 1)
	local root = state.root :: BasePart
	Drops.spawn(match.world.extras, root.Position + root.CFrame.LookVector * 3.5, { { id = id, count = 1 } }, function(other)
		local s = Session.get(other)
		return s ~= nil and s.match == match and s.location == "match"
	end, player, 1.5)
end)

Net.on("invClick", function(player, container, index, button)
	local state = Session.get(player)
	if not state or state.location ~= "match" or not state.alive then
		return
	end
	local inv = state.inv
	local mode = if button == "right" or button == "shift" then button else "left"
	if container == "inv" then
		if not isInt(index) or index < 1 or index > Inventory.Size then
			return
		end
		if mode == "shift" then
			local open = state.container
			if open then
				Inventory.quickMove(inv.slots, index, open.slots, open.size)
				Containers.refresh(open)
			else
				Inventory.quickMoveInside(inv, index)
			end
			inv.dirty = true
		else
			Inventory.click(inv, inv.slots, index, mode)
		end
	elseif container == "chest" then
		local open = state.container
		if not open or not isInt(index) or index < 1 or index > open.size then
			return
		end
		if mode == "shift" then
			Inventory.quickMove(open.slots, index, inv.slots, Inventory.Size)
			inv.dirty = true
		else
			Inventory.click(inv, open.slots, index, mode)
		end
		Containers.refresh(open)
	elseif container == "armor" then
		if type(index) == "string" and table.find(Items.ArmorSlots, index) then
			Inventory.clickArmor(inv, index)
		end
	end
end)

Net.on("closeContainer", function(player)
	Containers.close(player)
end)

Net.on("buy", function(player, key)
	local state, match = playing(player)
	if state and match and match.modeId == "bedwars" and type(key) == "string" then
		match.mode.buy(match, player, key)
	end
end)

Net.on("upgrade", function(player, key)
	local state, match = playing(player)
	if state and match and match.modeId == "bedwars" and type(key) == "string" then
		match.mode.upgrade(match, player, key)
	end
end)

Net.on("join", function(player, modeId)
	if type(modeId) == "string" then
		Matches.join(player, modeId)
	end
end)

Net.on("leave", function(player)
	Matches.leave(player)
end)

Net.on("vote", function(player, mapId)
	if type(mapId) == "string" then
		Matches.vote(player, mapId)
	end
end)

Net.on("fall", function(player, blocks)
	local state, match = playing(player)
	if not state or not match or match.mode.noFallDamage or type(blocks) ~= "number" or blocks ~= blocks then
		return
	end
	local damage = math.floor(math.clamp(blocks, 0, 60) - Config.Combat.FallSafeBlocks + 0.5)
	if damage > 0 then
		Combat.damage(player, damage, { cause = "fall", ignoreArmor = true })
	end
end)

Net.on("doubleJump", function(player)
	local state, match = playing(player)
	if state and match and match.mode.doubleJump then
		match.mode.doubleJump(match, player)
	end
end)

return Actions
