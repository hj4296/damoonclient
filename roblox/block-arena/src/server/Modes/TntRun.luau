-- TNT 런: 밟은 바닥이 잠시 뒤 사라집니다. 더블 점프 3번, 마지막까지 버틴 사람이 승리.
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Util = require(Shared.Util)
local Server = script.Parent.Parent
local Session = require(Server.Session)
local Net = require(Server.Net)
local Inventory = require(Server.Logic.Inventory)

local B = Config.BlockSize
local TR = Config.TntRun

local TntRun = {}
local Matches: any = nil

function TntRun.init(matches)
	Matches = matches
end

local random = Random.new()

-- 캐릭터 발밑 네 귀퉁이 + 가운데
local FOOT_OFFSETS = {
	Vector3.new(0, 0, 0),
	Vector3.new(0.8, 0, 0.8),
	Vector3.new(-0.8, 0, 0.8),
	Vector3.new(0.8, 0, -0.8),
	Vector3.new(-0.8, 0, -0.8),
}

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
			Matches.finish(match, {}, string.format("기록: %s 버텼습니다!", Util.formatTime(match.data.elapsed - match.data.runStart)))
		end
		return
	end
	if #alive <= 1 then
		local winner = alive[1]
		if winner then
			Matches.finish(match, { winner }, Matches.coloredName(winner) .. "님이 끝까지 버텼습니다!")
		else
			Matches.finish(match, {}, "무승부!")
		end
	end
end

function TntRun.start(match, markers)
	local world = match.world
	local data = {
		players = {},
		floors = {},
		phase = "countdown",
		countdown = TR.StartDelay,
		lastShown = -1,
		elapsed = 0,
		runStart = 0,
		scheduled = {},
		eliminatedCount = 0,
	}
	match.data = data
	local lowest = 0
	for _, y in markers.floor or {} do
		data.floors[y] = true
		lowest = math.min(lowest, y)
	end
	match.voidY = match.origin.Y + (lowest - 4) * B

	local spawns = table.clone(markers.spawn or {})
	for i = #spawns, 2, -1 do
		local j = math.clamp(1 + math.floor(random:NextNumber() * i), 1, i)
		spawns[i], spawns[j] = spawns[j], spawns[i]
	end
	for index, player in match.players do
		local spawn = spawns[(index - 1) % math.max(1, #spawns) + 1]
		data.players[player] = { eliminated = false, survived = 0 }
		local state = Session.get(player)
		if state then
			state.team = nil
			Inventory.clear(state.inv)
		end
		player:SetAttribute("DoubleJumps", TR.DoubleJumps)
		Session.setContext(player, { location = "match", mode = "tntrun", build = false, pvp = false, doubleJump = true })
		if spawn then
			Session.spawn(player, world:standCFrame(spawn.x, spawn.y, spawn.z, world:cellCenter(0, spawn.y, 0)))
		end
	end
	Session.title(match.players, '<font color="#FFAA00">TNT 런</font>', "멈추면 떨어집니다!", 3)
	Session.chat(match.players, '<font color="#FFAA00">━━━━━━━━ TNT 런 ━━━━━━━━</font>')
	Session.chat(match.players, "밟은 블록은 곧 사라집니다. 계속 달리세요!")
	Session.chat(match.players, "공중에서 스페이스를 한 번 더 누르면 더블 점프 (3번).")
end

function TntRun.tick(match, dt: number)
	local data = match.data
	data.elapsed += dt
	if data.phase == "countdown" then
		data.countdown -= dt
		local shown = math.ceil(data.countdown)
		if shown ~= data.lastShown and shown > 0 then
			data.lastShown = shown
			Session.title(match.players, string.format('<font color="#FFFF55">%d</font>', shown), "", 1)
			Net.sendMany(match.players, "sound", "tick")
		end
		if data.countdown <= 0 then
			data.phase = "run"
			data.runStart = data.elapsed
			Session.title(match.players, '<font color="#55FF55">달려!</font>', "", 1.2)
			Net.sendMany(match.players, "sound", "start")
		end
		return
	end

	local world = match.world
	local now = data.elapsed
	for player, pd in data.players do
		local state = Session.get(player)
		local root = state and state.root
		local humanoid = state and state.humanoid
		if not pd.eliminated and state and state.alive and root and humanoid then
			local feet = root.Position - Vector3.new(0, humanoid.HipHeight + root.Size.Y / 2 + 0.3, 0)
			for _, offset in FOOT_OFFSETS do
				local x, y, z = world:toCell(feet + offset)
				if data.floors[y] then
					local record = world:get(x, y, z)
					if record then
						local key = x .. "," .. y .. "," .. z
						if not data.scheduled[key] then
							data.scheduled[key] = { time = now + TR.RemoveDelay, x = x, y = y, z = z }
							record.part.Color = record.part.Color:Lerp(Color3.fromRGB(255, 60, 40), 0.45)
						end
					end
				end
			end
		end
	end
	for key, entry in data.scheduled do
		if now >= entry.time then
			world:remove(entry.x, entry.y, entry.z)
			data.scheduled[key] = nil
		end
	end
	if now - data.runStart >= TR.TimeLimit then
		Matches.finish(match, aliveList(match), "시간 초과! 살아남은 모두가 승리!")
	end
end

function TntRun.onDeath(match, player: Player, _killer: Player?, _cause: string)
	local data = match.data
	local pd = data.players[player]
	if pd and not pd.eliminated then
		pd.eliminated = true
		pd.survived = data.elapsed - data.runStart
		data.eliminatedCount += 1
		local left = #aliveList(match)
		Session.chat(
			match.players,
			string.format('%s님이 떨어졌습니다! <font color="#AAAAAA">(%s 버팀, 남은 인원 %d명)</font>', Matches.coloredName(player), Util.formatTime(pd.survived), left)
		)
	end
	player:SetAttribute("DoubleJumps", nil)
	Matches.spectate(player)
	checkWin(match)
end

function TntRun.onLeave(match, player: Player)
	local pd = match.data.players[player]
	if pd and not pd.eliminated then
		pd.eliminated = true
	end
	player:SetAttribute("DoubleJumps", nil)
	checkWin(match)
end

function TntRun.doubleJump(match, player: Player): boolean
	local pd = match.data.players[player]
	local left = player:GetAttribute("DoubleJumps")
	if match.data.phase ~= "run" or not pd or pd.eliminated or type(left) ~= "number" or left <= 0 then
		return false
	end
	player:SetAttribute("DoubleJumps", left - 1)
	return true
end

function TntRun.canDamage(): boolean
	return false
end

function TntRun.canPlace(): boolean
	return false
end

function TntRun.canBreak(): boolean
	return false
end

function TntRun.canExplode(): boolean
	return false
end

TntRun.noFallDamage = true

function TntRun.sidebar(match, player: Player)
	local data = match.data
	local lines = { "" }
	table.insert(lines, string.format('남은 플레이어: <font color="#55FF55">%d</font>', #aliveList(match)))
	local jumps = player:GetAttribute("DoubleJumps")
	if type(jumps) == "number" then
		table.insert(lines, string.format('더블 점프: <font color="#55FF55">%d</font>', jumps))
	end
	table.insert(lines, "")
	if data.phase == "countdown" then
		table.insert(lines, string.format('시작까지: <font color="#55FF55">%d초</font>', math.max(0, math.ceil(data.countdown))))
	else
		table.insert(lines, string.format('시간: <font color="#55FF55">%s</font>', Util.formatTime(data.elapsed - data.runStart)))
	end
	table.insert(lines, "")
	table.insert(lines, '<font color="#AAAAAA">맵: ' .. (if match.map then match.map.name else "") .. "</font>")
	return { title = '<font color="#FFAA00">TNT 런</font>', lines = lines }
end

function TntRun.cleanup(match)
	for player in match.data.players or {} do
		if player.Parent then
			player:SetAttribute("DoubleJumps", nil)
		end
	end
	match.data = {}
end

return TntRun
