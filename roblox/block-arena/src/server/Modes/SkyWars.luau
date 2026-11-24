-- 스카이워즈: 유리관에서 시작, 키트와 능력, 상자에서 장비 수집, 럭키 블록, 마지막 한 명이 승리
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Blocks = require(Shared.Blocks)
local Items = require(Shared.Items)
local Util = require(Shared.Util)
local Server = script.Parent.Parent
local Session = require(Server.Session)
local Net = require(Server.Net)
local Inventory = require(Server.Logic.Inventory)
local Loot = require(Server.Logic.Loot)
local Containers = require(Server.Containers)
local Drops = require(Server.Drops)
local Combat = require(Server.Combat)
local Lucky = require(Server.Lucky)
local Kits = require(Shared.Kits)

local B = Config.BlockSize
local SW = Config.SkyWars

local SkyWars = {}
local Matches: any = nil

function SkyWars.init(matches)
	Matches = matches
end

local random = Random.new()
local function rand(): number
	return random:NextNumber()
end

local CAGE_OFFSETS = {}
for dx = -1, 1 do
	for dz = -1, 1 do
		if dx ~= 0 or dz ~= 0 then
			table.insert(CAGE_OFFSETS, { dx, 1, dz })
			table.insert(CAGE_OFFSETS, { dx, 2, dz })
		end
	end
end
table.insert(CAGE_OFFSETS, { 0, 3, 0 })

local function fillChest(chest, starter: boolean)
	table.clear(chest.container.slots)
	local stacks = Loot.roll(chest.tier, rand)
	if starter then
		for _, stack in Loot.starter(rand) do
			table.insert(stacks, 1, stack)
		end
	end
	local scattered = Loot.scatter(stacks, chest.container.size, rand)
	for index, stack in scattered do
		chest.container.slots[index] = stack
	end
	Containers.refresh(chest.container)
end

-- 키트 아이템 주기 (갑옷은 비어 있는 자리에 바로 입힘)
local function giveKit(player: Player)
	local state = Session.get(player)
	if not state then
		return
	end
	local kit = Kits.get(state.kit) or Kits.get(Kits.DefaultKit)
	if not kit then
		return
	end
	for _, stack in kit.items do
		local def = Items.get(stack.id)
		if def and def.kind == "armor" and def.armorSlot and not state.inv.armor[def.armorSlot] then
			state.inv.armor[def.armorSlot] = stack.id
			state.inv.dirty = true
		else
			Inventory.add(state.inv, stack.id, stack.count)
		end
	end
	local perk = Kits.getPerk(state.perk)
	Session.chat({ player }, string.format('키트 <font color="#55FF55">%s</font>와(과) 능력 <font color="#55FFFF">%s</font>을(를) 받았습니다.', kit.name, if perk then perk.name else "없음"))
end

local function perkOf(player: Player): string?
	local state = Session.get(player)
	return if state then state.perk else nil
end

local function aliveList(match): { Player }
	local out = {}
	for player, pd in match.data.players do
		local state = Session.get(player)
		if not pd.eliminated and player.Parent and state and state.match == match then
			table.insert(out, player)
		end
	end
	return out
end

local function checkWin(match)
	if match.state ~= "playing" then
		return
	end
	local alive = aliveList(match)
	if match.practice then
		if #alive == 0 then
			Matches.finish(match, {}, "연습이 끝났습니다.")
		end
		return
	end
	if #alive <= 1 then
		local winner = alive[1]
		if winner then
			Matches.finish(match, { winner }, Matches.coloredName(winner) .. "님이 마지막까지 살아남았습니다!")
		else
			Matches.finish(match, {}, "무승부!")
		end
	end
end

function SkyWars.start(match, markers)
	local world = match.world
	local data = {
		players = {},
		chests = {},
		elapsed = 0,
		phase = "cage",
		cageLeft = SW.CageTime,
		lastShown = -1,
		fightStart = 0,
		refillIndex = 1,
		cages = {},
		lucky = 0,
	}
	match.data = data
	match.voidY = match.origin.Y - 30 * B

	local spawns = table.clone(markers.spawn or {})
	for i = #spawns, 2, -1 do
		local j = math.clamp(1 + math.floor(rand() * i), 1, i)
		spawns[i], spawns[j] = spawns[j], spawns[i]
	end

	-- 상자 만들기
	for _, marker in markers.chest or {} do
		local record = world:get(marker.x, marker.y, marker.z)
		local chest = {
			tier = marker.tier,
			x = marker.x,
			y = marker.y,
			z = marker.z,
			container = Containers.new(if marker.tier == "center" then "가운데 상자" else "상자", 27),
			starter = false,
		}
		table.insert(data.chests, chest)
		if record and record.part then
			local prompt = Instance.new("ProximityPrompt")
			prompt.ActionText = "열기"
			prompt.ObjectText = chest.container.title
			prompt.KeyboardKeyCode = Enum.KeyCode.F
			prompt.HoldDuration = 0
			prompt.MaxActivationDistance = 10
			prompt.RequiresLineOfSight = false
			prompt.Parent = record.part :: BasePart
			prompt.Triggered:Connect(function(player)
				local state = Session.get(player)
				if state and state.match == match and state.location == "match" and state.alive and data.phase == "fight" then
					Containers.open(player, chest.container)
				end
			end)
		end
	end
	-- 섬마다 가장 가까운 상자 하나에 기본 장비
	for _, spawn in spawns do
		local best, bestDistance = nil, math.huge
		for _, chest in data.chests do
			if chest.tier == "island" and not chest.starter then
				local d = (chest.x - spawn.x) ^ 2 + (chest.z - spawn.z) ^ 2
				if d < bestDistance then
					best, bestDistance = chest, d
				end
			end
		end
		if best and bestDistance < 50 then
			best.starter = true
		end
	end
	for _, chest in data.chests do
		fillChest(chest, chest.starter)
	end

	-- 럭키 블록: 표시된 자리 중 바닥이 있고 비어 있는 곳에 확률로
	for _, spot in markers.lucky or {} do
		local record = world:get(spot.x, spot.y, spot.z)
		local def = if record then Blocks.get(record.id) else nil
		local free = record == nil or (def ~= nil and def.canCollide == false and not def.water and not def.burn)
		if free and world:isSolid(spot.x, spot.y - 1, spot.z) and rand() < SW.LuckyChance then
			world:set(spot.x, spot.y, spot.z, "lucky_block")
			data.lucky += 1
		end
	end

	for index, player in match.players do
		local spawn = spawns[index]
		if spawn then
			data.players[player] = { kills = 0, eliminated = false, spawn = spawn }
			for _, o in CAGE_OFFSETS do
				local x, y, z = spawn.x + o[1], spawn.y - 1 + o[2], spawn.z + o[3]
				-- 유리관이 덮은 장식 블록은 기억했다가 유리관이 열리면 되돌려 놓음
				local old = world:get(x, y, z)
				table.insert(data.cages, { x, y, z, if old then old.id else nil })
				world:set(x, y, z, "cage_glass")
			end
			local state = Session.get(player)
			if state then
				state.team = nil
				Inventory.clear(state.inv)
			end
			Session.setContext(player, { location = "match", mode = "skywars", build = true, pvp = true })
			Session.spawn(player, world:standCFrame(spawn.x, spawn.y, spawn.z, world:cellCenter(0, spawn.y, 0)))
		else
			Matches.spectate(player)
		end
	end
	Session.title(match.players, '<font color="#55FFFF">스카이워즈</font>', "상자에서 장비를 모아 살아남으세요!", 3)
	Session.chat(match.players, '<font color="#55FFFF">━━━━━━━━ 스카이워즈 ━━━━━━━━</font>')
	Session.chat(match.players, "유리관이 열리면 상자(F)에서 장비를 챙기세요. 가운데 섬에 좋은 장비가 있습니다.")
	Session.chat(match.players, "블록을 부수면 그 블록을 얻습니다. 마지막까지 살아남은 사람이 승리!")
	if data.lucky > 0 then
		Session.chat(match.players, string.format('<font color="#FFD24A">럭키 블록 %d개</font>가 섬 곳곳에 있어요. 부숴 보면 좋은 일이… 아니면 나쁜 일이!', data.lucky))
	end
end

function SkyWars.tick(match, dt: number)
	local data = match.data
	data.elapsed += dt
	if data.phase == "cage" then
		data.cageLeft -= dt
		local shown = math.ceil(data.cageLeft)
		if shown ~= data.lastShown and shown > 0 then
			data.lastShown = shown
			if shown <= 5 then
				Session.title(match.players, string.format('<font color="#FFFF55">%d</font>', shown), "유리관이 곧 열립니다", 1)
				Net.sendMany(match.players, "sound", "tick")
			end
		end
		if data.cageLeft <= 0 then
			data.phase = "fight"
			data.fightStart = data.elapsed
			for _, c in data.cages do
				match.world:remove(c[1], c[2], c[3])
				if c[4] then
					match.world:set(c[1], c[2], c[3], c[4])
				end
			end
			Session.title(match.players, '<font color="#FF5555">싸워라!</font>', "", 1.5)
			Net.sendMany(match.players, "sound", "start")
			for player, pd in data.players do
				if not pd.eliminated then
					giveKit(player)
					if perkOf(player) == "bulwark" then
						Combat.setAbsorption(player, 4, 90)
					end
				end
			end
		end
		return
	end

	local fightTime = data.elapsed - data.fightStart
	local refill = SW.Refills[data.refillIndex]
	if refill and fightTime >= refill then
		data.refillIndex += 1
		for _, chest in data.chests do
			fillChest(chest, false)
		end
		Session.chat(match.players, '<font color="#FFFF55">모든 상자가 다시 채워졌습니다!</font>')
		Session.title(match.players, "", '<font color="#FFFF55">상자 리필!</font>', 2)
	end
	if fightTime >= SW.TimeLimit then
		local alive = aliveList(match)
		Matches.finish(match, if #alive == 1 then alive else {}, "시간 초과!")
	end
end

function SkyWars.onDeath(match, player: Player, killer: Player?, cause: string)
	local data = match.data
	local pd = data.players[player]
	local state = Session.get(player)
	if pd and not pd.eliminated then
		pd.eliminated = true
		if killer and data.players[killer] then
			data.players[killer].kills += 1
			local perk = perkOf(killer)
			if perk == "lifesteal" then
				Combat.heal(killer, 8)
			elseif perk == "bulwark" then
				Combat.setAbsorption(killer, 4, 60)
			end
		end
		-- 아이템 떨구기 (공허에 떨어진 경우는 없어짐)
		if state and state.root and cause ~= "void" then
			local stacks = Drops.takeAll(state.inv)
			Drops.spawn(match.world.extras, state.root.Position, stacks, function(other)
				local s = Session.get(other)
				return s ~= nil and s.match == match and s.location == "match"
			end)
		end
		Matches.deathMessage(match, player, killer, cause, false)
		local left = #aliveList(match)
		Session.chat(match.players, string.format('<font color="#AAAAAA">남은 인원: %d명</font>', left))
	end
	Matches.spectate(player)
	Session.title({ player }, '<font color="#FF5555">탈락!</font>', "관전 중입니다. L 키로 로비에 갈 수 있어요", 4)
	checkWin(match)
end

function SkyWars.onLeave(match, player: Player)
	local pd = match.data.players[player]
	if pd and not pd.eliminated then
		pd.eliminated = true
		Session.chat(match.players, Matches.coloredName(player) .. "님이 게임을 떠났습니다.")
	end
	checkWin(match)
end

function SkyWars.canDamage(match, attacker: Player, victim: Player): boolean
	local data = match.data
	local a, v = data.players[attacker], data.players[victim]
	return data.phase == "fight" and a ~= nil and v ~= nil and not a.eliminated and not v.eliminated
end

function SkyWars.canPlace(match, _player: Player, x: number, y: number, z: number): boolean
	return match.data.phase == "fight" and y <= 30 and y >= -20 and x * x + z * z <= 60 * 60
end

function SkyWars.canBreak(match, _player: Player, record): boolean
	local def = Blocks.get(record.id)
	return match.data.phase == "fight" and def ~= nil and not def.unbreakable and not def.chest
end

function SkyWars.onBreak(match, player: Player, record)
	local def = Blocks.get(record.id)
	if def and def.lucky then
		Lucky.open(match, player, record.x, record.y, record.z)
		return
	end
	local state = Session.get(player)
	if state and Items.get(record.id) then
		local count = if perkOf(player) == "lucky_miner" and rand() < 0.25 then 2 else 1
		Inventory.add(state.inv, record.id, count)
	end
end

-- 능력: 깃털 낙하, 단단한 몸, 화살 회수
function SkyWars.fallScale(_match, player: Player): number
	return if perkOf(player) == "feather" then 0.5 else 1
end

function SkyWars.knockbackScale(_match, player: Player): number
	return if perkOf(player) == "sturdy" then 0.65 else 1
end

function SkyWars.onArrowHit(_match, player: Player)
	local state = Session.get(player)
	if state and perkOf(player) == "arrow_return" and rand() < 0.3 then
		Inventory.add(state.inv, "arrow", 1)
	end
end

function SkyWars.canExplode(_match, record): boolean
	local def = Blocks.get(record.id)
	return def ~= nil and not def.blastProof and not def.unbreakable and not def.chest
end

function SkyWars.sidebar(match, player: Player)
	local data = match.data
	local lines = { "" }
	table.insert(lines, string.format('남은 플레이어: <font color="#55FF55">%d</font>', #aliveList(match)))
	table.insert(lines, "")
	if data.phase == "cage" then
		table.insert(lines, string.format('시작까지: <font color="#55FF55">%d초</font>', math.max(0, math.ceil(data.cageLeft))))
	else
		local fightTime = data.elapsed - data.fightStart
		local refill = SW.Refills[data.refillIndex]
		if refill then
			table.insert(lines, string.format('상자 리필: <font color="#55FF55">%s</font>', Util.formatTime(refill - fightTime)))
		else
			table.insert(lines, string.format('게임 종료: <font color="#55FF55">%s</font>', Util.formatTime(SW.TimeLimit - fightTime)))
		end
	end
	table.insert(lines, "")
	local pd = data.players[player]
	if pd then
		table.insert(lines, string.format('처치: <font color="#55FF55">%d</font>', pd.kills))
		table.insert(lines, "")
	end
	table.insert(lines, '<font color="#AAAAAA">맵: ' .. (if match.map then match.map.name else "") .. "</font>")
	return { title = '<font color="#55FFFF">스카이워즈</font>', lines = lines }
end

function SkyWars.cleanup(match)
	for _, chest in match.data.chests or {} do
		Containers.closeAll(chest.container)
	end
	match.data = {}
end

return SkyWars
