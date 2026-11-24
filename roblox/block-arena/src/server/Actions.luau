-- 클라이언트 요청 처리: 블록 설치/부수기, 공격(사람/몹), 아이템 사용(물약/양동이/알/타워/낚싯대), 가방, 상점, 투표, 키트
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
local Blast = require(script.Parent.Blast)
local Drops = require(script.Parent.Drops)
local Status = require(script.Parent.Status)
local Mobs = require(script.Parent.Mobs)
local Kits = require(Shared.Kits)
local Monetization = require(script.Parent.Monetization)
local PlayerData = require(script.Parent.PlayerData)
local Skins = require(Shared.Skins)
local Preferences = require(Shared.Preferences)

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
	Blast.explode(match, position, radius, power, source)
end

local HORIZONTAL = {
	{ 1, 0 },
	{ -1, 0 },
	{ 0, 1 },
	{ 0, -1 },
}

-- 물/용암처럼 블록을 놓으면 덮어써지는 칸
local function replaceable(record): boolean
	if not record then
		return true
	end
	local def = Blocks.get(record.id)
	return record.placed and def ~= nil and (def.water == true or def.burn == true)
end

-- 사다리를 붙일 벽 방향 (누른 면의 반대쪽이 우선)
local function ladderFace(world, x: number, y: number, z: number, nx: any, nz: any): { number }?
	if isInt(nx, nz) and math.abs(nx) + math.abs(nz) == 1 and world:isSolid(x - nx, y, z - nz) then
		return { -nx, -nz }
	end
	for _, d in HORIZONTAL do
		if world:isSolid(x + d[1], y, z + d[2]) then
			return { d[1], d[2] }
		end
	end
	return nil
end

-- 스펀지: 주변(반지름 3칸)의 놓인 물/용암을 빨아들임
local function absorb(world, x: number, y: number, z: number)
	for dx = -3, 3 do
		for dy = -3, 3 do
			for dz = -3, 3 do
				local record = world:get(x + dx, y + dy, z + dz)
				local def = if record then Blocks.get(record.id) else nil
				if record and record.placed and def and (def.water or def.burn) then
					world:remove(record.x, record.y, record.z)
				end
			end
		end
	end
end

-- 양동이: 그 칸과 아래로 몇 칸 흘러내림
local function pour(match, player: Player, x: number, y: number, z: number, liquid: string)
	local world = match.world
	world:set(x, y, z, liquid, true)
	for d = 1, (if liquid == "lava_flow" then 3 else 5) do
		local below = world:get(x, y - d, z)
		if below or not match.mode.canPlace(match, player, x, y - d, z) then
			break
		end
		world:set(x, y - d, z, liquid, true)
	end
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

-- 팝업 타워: 그 자리에 사다리가 달린 양털 탑 (앞쪽은 문, 위에는 난간 있는 발판)
local function popupTower(match, player: Player, x: number, y: number, z: number, forward: Vector3): boolean
	local world = match.world
	local state = Session.get(player)
	local wool = "wool_" .. (if state and state.team then state.team else "white")
	if not Blocks.get(wool) then
		wool = "wool_white"
	end
	local fx, fz = 0, 1
	if math.abs(forward.X) > math.abs(forward.Z) then
		fx, fz = if forward.X > 0 then 1 else -1, 0
	else
		fx, fz = 0, if forward.Z > 0 then 1 else -1
	end
	local sx, sz = fz, -fx
	local placed = 0
	local function put(s: number, h: number, f: number, id: string, data: { [string]: any }?)
		local cx, cy, cz = x + s * sx + f * fx, y + h, z + s * sz + f * fz
		-- 사람이 서 있는 칸에는 벽을 만들지 않음 (끼임 방지)
		local solid = id ~= "ladder"
		if solid and blockedByCharacter(match, world:cellCenter(cx, cy, cz)) then
			return
		end
		if replaceable(world:get(cx, cy, cz)) and match.mode.canPlace(match, player, cx, cy, cz) then
			world:set(cx, cy, cz, id, true, data)
			placed += 1
		end
	end
	-- 벽 (뒤쪽 = 플레이어가 보는 방향 반대, 앞쪽 아래 두 칸은 입구)
	for h = 0, 4 do
		for s = -1, 1 do
			for f = -1, 1 do
				if not (s == 0 and f == 0) and not (s == 0 and f == -1 and h <= 1) then
					put(s, h, f, wool)
				end
			end
		end
	end
	-- 발판과 난간
	for s = -2, 2 do
		for f = -2, 2 do
			if not (s == 0 and f == 0) then
				put(s, 5, f, wool)
				if math.abs(s) == 2 or math.abs(f) == 2 then
					put(s, 6, f, wool)
				end
			end
		end
	end
	-- 가운데 사다리 (앞쪽 벽에 붙음)
	for h = 0, 5 do
		put(0, h, 0, "ladder", { face = { fx, fz } })
	end
	return placed > 0
end

Net.on("place", function(player, x, y, z, nx, _ny, nz)
	if not isInt(x, y, z) then
		return
	end
	local state, match = playing(player)
	if not state or not match then
		return
	end
	local stack, index = Inventory.selectedStack(state.inv)
	local def = if stack then Items.get(stack.id) else nil
	if not stack or not def then
		return
	end
	local kind = def.kind
	if kind ~= "block" and kind ~= "bucket" and kind ~= "deploy" then
		return
	end
	local world = match.world
	if not replaceable(world:get(x, y, z)) then
		return
	end
	local center = world:cellCenter(x, y, z)
	local root = state.root :: BasePart
	if (center - root.Position).Magnitude > Config.Reach.BlockServer then
		return
	end
	local touching = false
	for _, n in NEIGHBORS do
		local other = world:get(x + n[1], y + n[2], z + n[3])
		if other and not replaceable(other) then
			touching = true
			break
		end
	end
	if not touching or not match.mode.canPlace(match, player, x, y, z) then
		return
	end

	if kind == "bucket" and def.liquid then
		Inventory.consumeSlot(state.inv, index, 1)
		Net.broadcast("swing", player)
		pour(match, player, x, y, z, def.liquid)
		Net.send(player, "sound", "place")
		return
	elseif kind == "deploy" then
		if def.deploy == "tower" then
			local look = root.CFrame.LookVector
			if popupTower(match, player, x, y, z, Vector3.new(look.X, 0, look.Z)) then
				Inventory.consumeSlot(state.inv, index, 1)
				Net.broadcast("swing", player)
			end
		elseif def.deploy == "golem" then
			local position = Mobs.groundAt(match, x, y, z)
			if position and Mobs.spawn(match, "golem", position, { team = state.team, owner = player, life = Config.BedWars.GolemLife }) then
				Inventory.consumeSlot(state.inv, index, 1)
				Net.broadcast("swing", player)
				Session.chat({ player }, '<font color="#55FF55">수호 골렘이 4분 동안 기지를 지킵니다.</font>')
			end
		end
		return
	end

	if not def.block then
		return
	end
	local blockDef = Blocks.get(def.block)
	-- 사다리처럼 통과하는 블록은 서 있는 자리에도 놓을 수 있음
	if (blockDef == nil or blockDef.canCollide ~= false) and blockedByCharacter(match, center) then
		return
	end
	local data = nil
	if blockDef and blockDef.shape == "ladder" then
		local face = ladderFace(world, x, y, z, nx, nz)
		if not face then
			return
		end
		data = { face = face }
	end
	Inventory.consumeSlot(state.inv, index, 1)
	Net.broadcast("swing", player)
	local record = world:set(x, y, z, def.block, true, data)
	if record and blockDef and blockDef.tnt then
		Blast.prime(match, record, player)
	end
	if record and def.block == "sponge" then
		absorb(world, x, y, z)
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
	local haste = if match.mode.haste then match.mode.haste(match, player) else 0
	local need = Rules.breakTime(record.id, if stack then stack.id else nil, Rules.breakSpeed(haste, Status.has(player, "fatigue")))
	local breaking = state.breaking
	if not need or not breaking or breaking.key ~= World.key(x, y, z) then
		return
	end
	if os.clock() - breaking.time < need * 0.6 - 0.15 then
		return
	end
	state.breaking = nil
	Net.broadcast("swing", player)
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

Net.on("attackMob", function(player, mobId)
	local state = playing(player)
	if state then
		Mobs.melee(player, mobId)
	end
end)

local function launchArrow(player: Player, match, origin: Vector3, dir: Vector3, charge: number, bowId: string)
	local damage = Rules.arrowDamage(charge, bowId)
	local bow = Items.get(bowId)
	local punch = if bow and bow.punch then bow.punch else 1
	local function hit()
		Net.send(player, "sound", "arrowHit")
		if match.mode.onArrowHit then
			match.mode.onArrowHit(match, player)
		end
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
				local knock = (if push.Magnitude > 0.1 then push.Unit else Vector3.zero) * 14 * punch + Vector3.new(0, 10 * math.min(punch, 1.5), 0)
				Combat.damage(victim, damage, { attacker = player, cause = "arrow", knockback = knock })
				hit()
			end
		end,
		onHitMob = function(mob, _pos, velocity)
			if Mobs.canHit(player, mob) then
				local push = Vector3.new(velocity.X, 0, velocity.Z)
				Mobs.damage(mob, damage, player, (if push.Magnitude > 0.1 then push.Unit else Vector3.zero) * 10 * punch)
				hit()
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
	elseif kind == "bridge_egg" then
		-- 날아간 길 아래에 양털이 두 줄로 깔림
		local state = Session.get(player)
		local wool = "wool_" .. (if state and state.team then state.team else "white")
		if not Blocks.get(wool) then
			wool = "wool_white"
		end
		local world = match.world
		-- 다리 폭: 던진 방향에 수직인 축으로 한 칸 더
		local px, pz = if math.abs(dir.X) > math.abs(dir.Z) then 0 else 1, if math.abs(dir.X) > math.abs(dir.Z) then 1 else 0
		Projectiles.launch({
			shooter = player,
			origin = origin,
			velocity = Vector3.new(dir.X, math.clamp(dir.Y, -0.2, 0.25), dir.Z).Unit * 48,
			gravity = 6,
			lifetime = 1.15,
			size = Vector3.new(0.6, 0.8, 0.6),
			color = Color3.fromRGB(236, 226, 196),
			shape = Enum.PartType.Ball,
			onStep = function(position)
				if match.state ~= "playing" or (position - origin).Magnitude < 3 then
					return
				end
				local cx, cy, cz = world:toCell(position - Vector3.new(0, 2 * B, 0))
				for _, k in { 0, 1 } do
					local x, z = cx + px * k, cz + pz * k
					if replaceable(world:get(x, cy, z)) and match.mode.canPlace(match, player, x, cy, z) then
						world:set(x, cy, z, wool, true)
					end
				end
			end,
			onHitPlayer = function() end,
			onHitWorld = function() end,
		})
	elseif kind == "bed_bug" then
		local state = Session.get(player)
		local team = if state then state.team else nil
		local function release(position: Vector3)
			if match.state == "playing" then
				Mobs.spawn(match, "silverfish", position, { team = team, owner = player, life = Config.BedWars.BugLife })
			end
		end
		Projectiles.launch({
			shooter = player,
			origin = origin,
			velocity = dir * 80,
			gravity = 80,
			size = Vector3.new(0.7, 0.7, 0.7),
			color = Color3.fromRGB(150, 150, 156),
			shape = Enum.PartType.Ball,
			onHitPlayer = function(_victim, position)
				release(position - Vector3.new(0, 2, 0))
			end,
			onHitWorld = function(position, normal)
				release(position + normal * 0.6)
			end,
		})
	elseif kind == "bobber" then
		-- 낚싯찌: 맞은 사람을 내 쪽으로 끌어당김
		Projectiles.launch({
			shooter = player,
			origin = origin,
			velocity = dir * 70,
			gravity = 60,
			lifetime = 1.4,
			size = Vector3.new(0.4, 0.4, 0.4),
			color = Color3.fromRGB(220, 50, 50),
			shape = Enum.PartType.Ball,
			onHitPlayer = function(victim)
				local me = Session.get(player)
				local them = Session.get(victim)
				if Combat.canDamage(player, victim) and me and me.root and them and them.root then
					local toward = me.root.Position - them.root.Position
					local flat = Vector3.new(toward.X, 0, toward.Z)
					local pull = (if flat.Magnitude > 0.1 then flat.Unit else Vector3.zero) * 22 + Vector3.new(0, 16, 0)
					Combat.damage(victim, 0.5, { attacker = player, cause = "melee", knockback = pull })
				end
			end,
			onHitMob = function(mob)
				if Mobs.canHit(player, mob) then
					Mobs.damage(mob, 0.5, player)
				end
			end,
			onHitWorld = function() end,
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
		elseif def.action == "kits" then
			Net.send(player, "openKits", { kit = state.kit, perk = state.perk })
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
	elseif def.kind == "potion" and def.effect then
		Inventory.consumeSlot(state.inv, index, 1)
		if def.effect == "heal" then
			Combat.heal(player, 8)
		else
			Status.add(player, def.effect, def.duration or 30)
		end
		Net.send(player, "sound", "eat")
		local effect = Config.Effects[def.effect]
		if effect then
			Session.chat({ player }, string.format('<font color="%s">%s</font> 효과를 받았습니다.', effect.color, effect.name))
		end
	elseif def.kind == "rod" then
		if typeof(dir) ~= "Vector3" or dir.Magnitude < 0.5 or dir.Magnitude > 2 then
			return
		end
		if now - state.lastRod < 0.8 then
			return
		end
		state.lastRod = now
		local unit = dir.Unit
		throw(player, match, "bobber", (state.root :: BasePart).Position + Vector3.new(0, 1.5, 0) + unit * 2.5, unit)
		Net.broadcast("swing", player)
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
			launchArrow(player, match, origin, unit, power, stack.id)
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

Net.on("trap", function(player, key)
	local state, match = playing(player)
	if state and match and match.modeId == "bedwars" and type(key) == "string" then
		match.mode.buyTrap(match, player, key)
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
	if match.mode.fallScale then
		damage = math.floor(damage * match.mode.fallScale(match, player) + 0.5)
	end
	if damage > 0 then
		Combat.damage(player, damage, { cause = "fall", ignoreArmor = true })
	end
end)

-- 상점: 게임패스 구매 창
Net.on("buyPass", function(player, key)
	if type(key) == "string" and Config.Passes[key] then
		Monetization.prompt(player, key)
	end
end)

Net.on("passes", function(player)
	Monetization.send(player)
end)

-- 스킨 저장 (기본 스킨이 아니면 커스텀 스킨 패스 필요)
Net.on("setSkin", function(player, code)
	local state = Session.get(player)
	if not state or type(code) ~= "string" or #code > 120 then
		return
	end
	local clean = Skins.encode(Skins.decode(code))
	if clean ~= Skins.DefaultCode and not Monetization.owns(player, "skin") then
		Net.send(player, "chat", '<font color="#FF5555">스킨을 바꾸려면 커스텀 스킨 패스가 필요해요.</font>')
		Monetization.prompt(player, "skin")
		return
	end
	Session.setSkin(player, clean)
	state.savedSkin = clean
	Net.send(player, "skinSaved", clean)
	task.spawn(PlayerData.save, player)
end)

-- 설정 저장
Net.on("settings", function(player, settings)
	local state = Session.get(player)
	if state and type(settings) == "table" then
		state.settings = Preferences.sanitize(settings)
		Net.send(player, "settings", state.settings)
	end
end)

-- VIP 날기 중인지 (다른 사람에게 나는 자세로 보이게)
Net.on("flying", function(player, flying)
	local state = Session.get(player)
	local character = player.Character
	if not state or not character then
		return
	end
	local allowed = flying == true and Monetization.owns(player, "vip") and state.location == "lobby"
	character:SetAttribute("Flying", allowed)
end)

-- 스카이워즈 키트/능력 고르기 (대기실에서)
Net.on("kit", function(player, kitId, perkId)
	local state = Session.get(player)
	if not state then
		return
	end
	if type(kitId) == "string" and Kits.get(kitId) then
		state.kit = kitId
	end
	if type(perkId) == "string" and Kits.getPerk(perkId) then
		state.perk = perkId
	end
	local kit = Kits.get(state.kit)
	local perk = Kits.getPerk(state.perk)
	Net.send(player, "kitState", { kit = state.kit, perk = state.perk })
	if kit and perk then
		Session.chat({ player }, string.format('키트: <font color="#55FF55">%s</font> · 능력: <font color="#55FFFF">%s</font>', kit.name, perk.name))
	end
end)

Net.on("doubleJump", function(player)
	local state, match = playing(player)
	if state and match and match.mode.doubleJump then
		match.mode.doubleJump(match, player)
	end
end)

return Actions
