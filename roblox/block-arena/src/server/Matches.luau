-- 경기 관리: 대기열 -> 대기실(맵 투표) -> 게임 -> 결과 -> 로비
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local Players = game:GetService("Players")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Util = require(Shared.Util)
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)
local Combat = require(script.Parent.Combat)
local World = require(script.Parent.World)
local Inventory = require(script.Parent.Logic.Inventory)
local Vote = require(script.Parent.Logic.Vote)
local PlayerData = require(script.Parent.PlayerData)
local Rooms = require(script.Parent.Maps.Rooms)

local B = Config.BlockSize

local Matches = {}

local MODES = {
	bedwars = require(script.Parent.Modes.BedWars),
	skywars = require(script.Parent.Modes.SkyWars),
	tntrun = require(script.Parent.Modes.TntRun),
}
local MAPS = {
	bedwars = require(script.Parent.Maps.BedWarsMaps),
	skywars = require(script.Parent.Maps.SkyWarsMaps),
	tntrun = require(script.Parent.Maps.TntRunMaps),
}

local matches: { [number]: any } = {}
local usedSlots: { [number]: boolean } = {}
local nextId = 0
local lobbySpawn = CFrame.new(0, 120, 0)
local lobbyVoidY = -100

local random = Random.new()
local function rand(): number
	return random:NextNumber()
end

Matches.onLobbyInfo = function(_info: { [string]: { waiting: number, playing: number } }) end

function Matches.setLobby(spawn: CFrame, voidY: number)
	lobbySpawn = spawn
	lobbyVoidY = voidY
end

function Matches.lobbySpawn(): CFrame
	return lobbySpawn
end

local function minPlayers(def): number
	if RunService:IsStudio() then
		return math.min(def.minPlayers, Config.StudioMinPlayers)
	end
	return def.minPlayers
end

function Matches.teamHex(teamId: string?): string
	for _, team in Config.Teams do
		if team.id == teamId then
			return team.hex
		end
	end
	return "#FFFFFF"
end

function Matches.coloredName(player: Player): string
	local state = Session.get(player)
	local hex = if state and state.team then Matches.teamHex(state.team) else "#AAAAAA"
	return string.format('<font color="%s">%s</font>', hex, player.DisplayName)
end

function Matches.inGame(match): { Player }
	local out = {}
	for _, player in match.players do
		local state = Session.get(player)
		if state and state.location == "match" then
			table.insert(out, player)
		end
	end
	return out
end

local function giveLobbyItems(state)
	Inventory.clear(state.inv)
	state.inv.slots[1] = { id = "compass", count = 1 }
	state.inv.dirty = true
end

local function giveWaitingItems(state)
	Inventory.clear(state.inv)
	state.inv.slots[1] = { id = "vote_paper", count = 1 }
	state.inv.slots[9] = { id = "leave_bed", count = 1 }
	state.inv.dirty = true
end

local function freeSlot(): number?
	for i = 1, Config.MaxArenas do
		if not usedSlots[i] then
			return i
		end
	end
	return nil
end

local function destroyMatch(match)
	if match.destroyed then
		return
	end
	match.destroyed = true
	if match.mode.cleanup and match.state ~= "waiting" then
		local ok, err = pcall(match.mode.cleanup, match)
		if not ok then
			warn("[블록 아레나] 정리 중 오류:", err)
		end
	end
	for _, world in { match.room, match.world, match.spectatorWorld } do
		if world then
			world:destroy()
		end
	end
	matches[match.id] = nil
	usedSlots[match.slot] = nil
end

local function createMatch(modeId: string)
	local slot = freeSlot()
	if not slot then
		return nil
	end
	usedSlots[slot] = true
	nextId += 1
	local origin = Config.ArenaBase + Vector3.new(slot * Config.ArenaSpacing, 0, 0)
	local match = {
		id = nextId,
		modeId = modeId,
		mode = MODES[modeId],
		def = Config.Modes[modeId],
		slot = slot,
		origin = origin,
		state = "waiting",
		players = {},
		votes = {},
		countdown = nil,
		maps = MAPS[modeId],
		map = nil,
		voteLocked = false,
		data = {},
		practice = false,
		startedAt = 0,
		room = nil,
		roomSpawn = CFrame.new(origin),
		world = nil,
		markers = nil,
		spectatorWorld = nil,
		spectatorSpawn = nil,
		voidY = origin.Y - 40 * B,
		destroyed = false,
	}
	local room = World.new(origin + Vector3.new(0, 70 * B, 0), "WaitingRoom_" .. nextId)
	local markers = {}
	Rooms.waiting(room:builder(markers), Util.rng(1))
	room:show(workspace)
	match.room = room
	local s = markers.spawn[1]
	match.roomSpawn = room:standCFrame(s.x, s.y, s.z, room:cellCenter(0, 1, 5))
	matches[match.id] = match
	return match
end

function Matches.sendVote(match)
	local ids = {}
	for _, map in match.maps do
		table.insert(ids, map.id)
	end
	local counts = Vote.counts(match.votes, ids)
	local maps = {}
	for _, map in match.maps do
		table.insert(maps, { id = map.id, name = map.name, description = map.description, votes = counts[map.id] })
	end
	for _, player in match.players do
		Net.send(player, "vote", {
			mode = match.modeId,
			maps = maps,
			mine = match.votes[player],
			locked = match.voteLocked,
			chosen = if match.map then match.map.id else nil,
		})
	end
end

local function lockVote(match)
	if match.voteLocked then
		return
	end
	match.voteLocked = true
	local ids = {}
	for _, map in match.maps do
		table.insert(ids, map.id)
	end
	local winner = Vote.winner(match.votes, ids, rand)
	for _, map in match.maps do
		if map.id == winner then
			match.map = map
		end
	end
	match.map = match.map or match.maps[1]
	local counts = Vote.counts(match.votes, ids)
	Session.chat(
		match.players,
		string.format('맵 투표 결과: <font color="#FFFF55">%s</font> (%d표)', match.map.name, counts[match.map.id] or 0)
	)
	Matches.sendVote(match)
end

function Matches.vote(player: Player, mapId: string)
	local state = Session.get(player)
	local match = state and state.match
	if not match or match.state ~= "waiting" or match.voteLocked then
		return
	end
	for _, map in match.maps do
		if map.id == mapId then
			match.votes[player] = mapId
			Session.chat({ player }, string.format('<font color="#55FF55">%s</font>에 투표했습니다.', map.name))
			Matches.sendVote(match)
			return
		end
	end
end

local function removeFromMatch(match, player: Player)
	local index = table.find(match.players, player)
	if not index then
		return
	end
	table.remove(match.players, index)
	match.votes[player] = nil
	local state = Session.get(player)
	if match.state == "waiting" then
		Session.chat(match.players, string.format("%s님이 나갔습니다 (%d/%d)", player.DisplayName, #match.players, match.def.maxPlayers))
		Matches.sendVote(match)
	elseif match.state == "playing" and state and (state.location == "match" or state.location == "spectate") then
		local ok, err = pcall(match.mode.onLeave, match, player)
		if not ok then
			warn("[블록 아레나] 나가기 처리 오류:", err)
		end
	end
	if #match.players == 0 then
		destroyMatch(match)
	end
end

function Matches.toLobby(player: Player)
	local state = Session.get(player)
	if not state then
		return
	end
	local match = state.match
	if match then
		removeFromMatch(match, player)
	end
	state.match = nil
	state.team = nil
	state.location = "lobby"
	state.container = nil
	state.respawnToken = nil
	giveLobbyItems(state)
	player:SetAttribute("DoubleJumps", nil)
	Session.setContext(player, { location = "lobby" })
	Session.spawn(player, lobbySpawn)
end

function Matches.join(player: Player, modeId: string)
	local state = Session.get(player)
	local def = Config.Modes[modeId]
	if not state or not def or state.location ~= "lobby" then
		return
	end
	local match = nil
	for _, m in matches do
		if m.modeId == modeId and m.state == "waiting" and #m.players < def.maxPlayers then
			match = m
			break
		end
	end
	match = match or createMatch(modeId)
	if not match then
		Session.chat({ player }, "지금은 빈 경기장이 없어요. 잠시 뒤에 다시 시도해 주세요.")
		return
	end
	table.insert(match.players, player)
	state.match = match
	state.location = "waiting"
	state.team = nil
	giveWaitingItems(state)
	Session.setContext(player, { location = "waiting", mode = modeId })
	Session.spawn(player, match.roomSpawn)
	Session.chat(
		match.players,
		string.format('<font color="#FFFF55">%s</font>님이 참가했습니다 (%d/%d)', player.DisplayName, #match.players, def.maxPlayers)
	)
	Matches.sendVote(match)
	Net.send(player, "openVote")
end

function Matches.leave(player: Player)
	local state = Session.get(player)
	if state and state.location ~= "lobby" then
		Matches.toLobby(player)
	end
end

-- 탈락: 관전실로 보냅니다.
function Matches.spectate(player: Player)
	local state = Session.get(player)
	local match = state and state.match
	if not state or not match then
		return
	end
	state.location = "spectate"
	state.container = nil
	Inventory.clear(state.inv)
	state.inv.slots[9] = { id = "leave_bed", count = 1 }
	state.inv.dirty = true
	Session.setContext(player, { location = "spectate", mode = match.modeId })
	if match.spectatorSpawn then
		Session.spawn(player, match.spectatorSpawn)
	end
end

-- 몇 초 뒤 부활 (그동안은 관전실에서 기다림)
function Matches.respawnLater(player: Player, delay: number, getCFrame: () -> CFrame, onSpawn: ((Model) -> ())?)
	local state = Session.get(player)
	local match = state and state.match
	if not state or not match then
		return
	end
	local token = {}
	state.respawnToken = token
	state.waitingRespawn = true
	Inventory.clear(state.inv)
	state.inv.armorLocked = true
	if match.spectatorSpawn then
		Session.spawn(player, match.spectatorSpawn)
	end
	task.spawn(function()
		for i = delay, 1, -1 do
			if state.match ~= match or state.respawnToken ~= token then
				return
			end
			Session.title({ player }, '<font color="#FF5555">사망했습니다!</font>', string.format("%d초 뒤 부활합니다", i), 1.1)
			task.wait(1)
		end
		if state.match ~= match or state.respawnToken ~= token or match.state ~= "playing" or state.location ~= "match" then
			return
		end
		state.waitingRespawn = false
		Session.spawn(player, getCFrame(), onSpawn)
		Session.title({ player }, '<font color="#55FF55">부활했습니다!</font>', "", 1.5)
	end)
end

function Matches.deathMessage(match, victim: Player, killer: Player?, cause: string, final: boolean)
	local v = Matches.coloredName(victim)
	local text
	if killer then
		local k = Matches.coloredName(killer)
		if cause == "void" then
			text = string.format("%s님이 %s님에게 밀려 공허로 떨어졌습니다.", v, k)
		elseif cause == "arrow" then
			text = string.format("%s님이 %s님의 화살에 맞았습니다.", v, k)
		elseif cause == "explosion" then
			text = string.format("%s님이 %s님의 폭발에 휘말렸습니다.", v, k)
		elseif cause == "fall" then
			text = string.format("%s님이 %s님에게 밀려 떨어졌습니다.", v, k)
		else
			text = string.format("%s님이 %s님에게 처치당했습니다.", v, k)
		end
	else
		if cause == "void" then
			text = v .. "님이 공허로 떨어졌습니다."
		elseif cause == "fall" then
			text = v .. "님이 높은 곳에서 떨어졌습니다."
		elseif cause == "explosion" then
			text = v .. "님이 폭발에 휘말렸습니다."
		elseif cause == "lava" then
			text = v .. "님이 용암에 빠졌습니다."
		else
			text = v .. "님이 죽었습니다."
		end
	end
	if final then
		text ..= ' <font color="#55FFFF"><b>최종 처치!</b></font>'
	end
	Session.chat(match.players, text)
end

function Matches.finish(match, winners: { Player }, headline: string)
	if match.state ~= "playing" then
		return
	end
	match.state = "ending"
	local winnerSet = {}
	for _, player in winners do
		winnerSet[player] = true
		PlayerData.addWin(player)
	end
	for _, player in match.players do
		if winnerSet[player] then
			Session.title({ player }, '<font color="#FFFF55">승리!</font>', headline, 5)
		else
			Session.title({ player }, '<font color="#FF5555">게임 종료</font>', headline, 5)
		end
	end
	Session.chat(match.players, '<font color="#55FF55">━━━━━━━━━━━━━━━━</font>')
	Session.chat(match.players, headline)
	Session.chat(match.players, string.format("%d초 뒤 로비로 돌아갑니다.", Config.Waiting.EndDelay))
	Session.chat(match.players, '<font color="#55FF55">━━━━━━━━━━━━━━━━</font>')
	task.delay(Config.Waiting.EndDelay, function()
		for _, player in table.clone(match.players) do
			local state = Session.get(player)
			if state and state.match == match then
				Matches.toLobby(player)
			end
		end
		destroyMatch(match)
	end)
end

local function startMatch(match)
	lockVote(match)
	match.state = "starting"
	local world = World.new(match.origin, "Arena_" .. match.id)
	local markers = {}
	local ok, err = pcall(function()
		match.map.build(world:builder(markers), Util.rng(match.map.seed))
	end)
	if not ok then
		warn("[블록 아레나] 맵 생성 오류:", err)
	end
	world:show(workspace)
	match.world = world
	match.markers = markers

	local spectator = World.new(match.origin + Vector3.new(0, 40 * B, 0), "Spectator_" .. match.id)
	Rooms.spectator(spectator:builder({}), Util.rng(2))
	spectator:show(workspace)
	match.spectatorWorld = spectator
	match.spectatorSpawn = spectator:standCFrame(0, 1, 0)

	match.practice = #match.players < 2
	match.startedAt = os.clock()
	match.state = "playing"
	for _, player in match.players do
		local state = Session.get(player)
		if state then
			state.location = "match"
			state.container = nil
			state.waitingRespawn = false
		end
	end
	local started, startErr = pcall(match.mode.start, match, markers)
	if not started then
		warn("[블록 아레나] 게임 시작 오류:", startErr)
	end
	-- 모두 경기장으로 옮겨진 뒤 대기실을 치움 (먼저 치우면 잠깐 떨어질 수 있음)
	local room = match.room
	match.room = nil
	if room then
		task.delay(3, function()
			room:destroy()
		end)
	end
	if match.practice then
		Session.chat(match.players, '<font color="#FFAA00">혼자 연습하는 중이에요. L 키를 누르면 로비로 돌아갑니다.</font>')
	end
end

local function tickWaiting(match)
	local need = minPlayers(match.def)
	if #match.players < need then
		if match.countdown then
			match.countdown = nil
			Session.chat(match.players, '<font color="#FF5555">인원이 부족해서 카운트다운이 멈췄습니다.</font>')
		end
		return
	end
	match.countdown = match.countdown or Config.Waiting.Countdown
	if #match.players >= match.def.maxPlayers then
		match.countdown = math.min(match.countdown, Config.Waiting.FullCountdown)
	end
	match.countdown -= 1
	local c = match.countdown
	if c == 20 or c == 10 or (c <= 5 and c >= 1) then
		Session.chat(match.players, string.format('<font color="#FFFF55">%d초</font> 뒤 게임이 시작됩니다!', c))
		if c <= 5 then
			local color = if c <= 2 then "#FF5555" elseif c <= 3 then "#FFAA00" else "#FFFF55"
			Session.title(match.players, string.format('<font color="%s">%d</font>', color, c), "", 1)
			Net.sendMany(match.players, "sound", "tick")
		end
	end
	if c <= Config.Waiting.VoteLock then
		lockVote(match)
	end
	if c <= 0 then
		startMatch(match)
	end
end

-- 죽었을 때
Session.onDied = function(player: Player, state)
	local match = state.match
	if state.location == "lobby" then
		task.delay(1, function()
			if state.location == "lobby" and player.Parent then
				Session.spawn(player, lobbySpawn)
			end
		end)
		return
	end
	if not match then
		return
	end
	if state.location == "waiting" then
		task.delay(1, function()
			if state.match == match and state.location == "waiting" then
				Session.spawn(player, match.roomSpawn)
			end
		end)
	elseif state.location == "spectate" or state.waitingRespawn or match.state ~= "playing" then
		task.delay(1, function()
			if state.match == match and match.spectatorSpawn then
				Session.spawn(player, match.spectatorSpawn)
			end
		end)
	elseif state.location == "match" then
		local killer = nil
		if state.lastHit.by and os.clock() - state.lastHit.time <= Config.Combat.KillCredit then
			killer = state.lastHit.by
		end
		local cause = state.deathCause or state.lastHit.cause
		if killer and killer.Parent then
			PlayerData.addKill(killer)
		else
			killer = nil
		end
		local ok, err = pcall(match.mode.onDeath, match, player, killer, cause or "")
		if not ok then
			warn("[블록 아레나] 사망 처리 오류:", err)
			Matches.spectate(player)
		end
	end
end

-- 전투 규칙: 같은 경기, 게임 중, 모드 규칙 확인
Combat.canDamage = function(attacker: Player, victim: Player): boolean
	local a, v = Session.get(attacker), Session.get(victim)
	if not a or not v or a.match == nil or a.match ~= v.match then
		return false
	end
	local match = a.match
	if match.state ~= "playing" or a.location ~= "match" or v.location ~= "match" then
		return false
	end
	if a.waitingRespawn or v.waitingRespawn then
		return false
	end
	return match.mode.canDamage(match, attacker, victim)
end
Combat.getProtection = function(player: Player): number
	local state = Session.get(player)
	local match = state and state.match
	if match and match.mode.protection then
		return match.mode.protection(match, player)
	end
	return 0
end
Combat.getSharpness = function(player: Player): number
	local state = Session.get(player)
	local match = state and state.match
	if match and match.mode.sharpness then
		return match.mode.sharpness(match, player)
	end
	return 0
end
Combat.getRegenInterval = function(player: Player): number
	local state = Session.get(player)
	local match = state and state.match
	if match and match.mode.regenInterval then
		return match.mode.regenInterval(match, player)
	end
	return Config.Combat.RegenInterval
end

function Matches.lobbyInfo(): { [string]: { waiting: number, playing: number } }
	local info = {}
	for _, id in Config.ModeOrder do
		info[id] = { waiting = 0, playing = 0 }
	end
	for _, match in matches do
		local entry = info[match.modeId]
		if entry then
			if match.state == "waiting" then
				entry.waiting += #match.players
			else
				entry.playing += #match.players
			end
		end
	end
	return info
end

local function sidebarFor(player: Player, state)
	local match = state.match
	if state.location == "lobby" then
		local count = #Players:GetPlayers()
		return {
			title = Config.GameName,
			lines = {
				"",
				string.format('승리: <font color="#55FF55">%d</font>', state.stats.wins),
				string.format('처치: <font color="#55FF55">%d</font>', state.stats.kills),
				"",
				string.format("접속자: %d명", count),
				"",
				'<font color="#FFFF55">나침반을 우클릭하거나</font>',
				'<font color="#FFFF55">NPC에게 F 키</font>',
			},
		}
	elseif state.location == "waiting" and match then
		local status
		if match.countdown then
			status = string.format('시작까지: <font color="#55FF55">%d초</font>', math.max(0, match.countdown))
		else
			status = string.format("인원 기다리는 중 (%d명 필요)", minPlayers(match.def))
		end
		return {
			title = match.def.name,
			lines = {
				"",
				"맵: " .. (if match.map then '<font color="#55FF55">' .. match.map.name .. "</font>" else "투표 중"),
				string.format("인원: %d/%d", #match.players, match.def.maxPlayers),
				"",
				status,
				"",
				'<font color="#AAAAAA">종이 아이템으로 맵 투표</font>',
			},
		}
	elseif match and match.state ~= "waiting" then
		local ok, result = pcall(match.mode.sidebar, match, player)
		if ok and result then
			return result
		end
	end
	return { title = Config.GameName, lines = {} }
end

local tickAccumulator = 0
local secondAccumulator = 0
local lobbyAccumulator = 0
RunService.Heartbeat:Connect(function(dt)
	tickAccumulator += dt
	secondAccumulator += dt
	lobbyAccumulator += dt

	if tickAccumulator >= 0.05 then
		local step = tickAccumulator
		tickAccumulator = 0
		for _, match in matches do
			if match.state == "playing" then
				local ok, err = pcall(match.mode.tick, match, step)
				if not ok then
					warn("[블록 아레나] 게임 진행 오류:", err)
				end
			end
		end
		-- 떨어짐 확인
		for player, state in Session.all() do
			local root = state.root
			if state.alive and root and root.Parent then
				local y = root.Position.Y
				local match = state.match
				if state.location == "lobby" then
					if y < lobbyVoidY then
						Session.teleport(player, lobbySpawn)
					end
				elseif state.location == "waiting" and match then
					if y < match.roomSpawn.Y - 30 then
						Session.teleport(player, match.roomSpawn)
					end
				elseif (state.location == "spectate" or state.waitingRespawn) and match and match.spectatorSpawn then
					if y < match.spectatorSpawn.Y - 20 then
						Session.teleport(player, match.spectatorSpawn)
					end
				elseif state.location == "match" and match and y < match.voidY then
					if match.state == "playing" then
						Combat.damage(player, 1000, { cause = "void", ignoreArmor = true })
					elseif match.spectatorSpawn then
						Session.teleport(player, match.spectatorSpawn)
					end
				end
			end
		end
	end

	if secondAccumulator >= 1 then
		secondAccumulator = 0
		for _, match in matches do
			if match.state == "waiting" then
				tickWaiting(match)
			end
		end
		for player, state in Session.all() do
			Net.send(player, "sidebar", sidebarFor(player, state))
		end
	end

	if lobbyAccumulator >= 2 then
		lobbyAccumulator = 0
		local info = Matches.lobbyInfo()
		Matches.onLobbyInfo(info)
		for player, state in Session.all() do
			if state.location == "lobby" then
				Net.send(player, "lobbyInfo", info)
			end
		end
	end
end)

function Matches.playerRemoving(player: Player)
	local state = Session.get(player)
	if state and state.match then
		removeFromMatch(state.match, player)
		state.match = nil
	end
end

function Matches.getMode(modeId: string)
	return MODES[modeId]
end

for _, mode in MODES do
	mode.init(Matches)
end

return Matches
