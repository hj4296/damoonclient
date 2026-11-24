-- 로비 만들기: 블록 마을, 스폰, 게임 포털(들어가면 참가), NPC, 안내 홀로그램, 승리 순위, 파쿠르
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local Players = game:GetService("Players")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Util = require(Shared.Util)
local World = require(script.Parent.World)
local Npc = require(script.Parent.Npc)
local Session = require(script.Parent.Session)
local LobbyMap = require(script.Parent.Maps.Lobby)

local B = Config.BlockSize

local LobbyService = {}

LobbyService.spawn = CFrame.new(Config.LobbyOrigin + Vector3.new(0, 6, 0))
LobbyService.voidY = Config.LobbyOrigin.Y - 60

local stations: { [string]: Model } = {}
local portalInfo: { [string]: TextLabel } = {}
local leaderboardLines: { TextLabel } = {}

local function anchor(parent: Instance, position: Vector3, name: string): Part
	local part = Instance.new("Part")
	part.Name = name
	part.Anchored = true
	part.CanCollide = false
	part.CanQuery = false
	part.CanTouch = false
	part.Transparency = 1
	part.Size = Vector3.new(1, 1, 1)
	part.CFrame = CFrame.new(position)
	part.Parent = parent
	return part
end

local function hologram(parent: Instance, position: Vector3, size: Vector2, lines: { { text: string, color: Color3, scale: number } }): { TextLabel }
	local part = anchor(parent, position, "Hologram")
	local gui = Instance.new("BillboardGui")
	gui.Size = UDim2.new(size.X, 0, size.Y, 0)
	gui.LightInfluence = 0
	gui.MaxDistance = 140
	gui.Parent = part
	local total = 0
	for _, line in lines do
		total += line.scale
	end
	local labels = {}
	local y = 0
	for _, line in lines do
		local label = Instance.new("TextLabel")
		label.BackgroundTransparency = 1
		label.Position = UDim2.new(0, 0, y / total, 0)
		label.Size = UDim2.new(1, 0, line.scale / total, 0)
		label.Font = Enum.Font.Arcade
		label.TextScaled = true
		label.RichText = true
		label.TextColor3 = line.color
		label.TextStrokeTransparency = 0.25
		label.Text = line.text
		label.Parent = gui
		table.insert(labels, label)
		y += line.scale
	end
	return labels
end

local function playerFromHit(hit: BasePart): Player?
	local model = hit:FindFirstAncestorOfClass("Model")
	return if model then Players:GetPlayerFromCharacter(model) else nil
end

function LobbyService.build(onJoin: (Player, string) -> ())
	local world = World.new(Config.LobbyOrigin, "Lobby")
	local markers = {}
	LobbyMap.build(world:builder(markers), Util.rng(42))
	world:show(workspace)

	local s = markers.spawn[1]
	local look = world:cellCenter(s.lookAt[1], s.lookAt[2], s.lookAt[3])
	LobbyService.spawn = world:standCFrame(s.x, s.y, s.z, look)

	local spawnPad = Instance.new("SpawnLocation")
	spawnPad.Name = "LobbySpawn"
	spawnPad.Anchored = true
	spawnPad.CanCollide = false
	spawnPad.CanQuery = false
	spawnPad.CanTouch = false
	spawnPad.Transparency = 1
	spawnPad.Duration = 0
	spawnPad.Neutral = true
	spawnPad.Size = Vector3.new(6, 1, 6)
	spawnPad.CFrame = CFrame.new(world:cellCenter(s.x, s.y, s.z) - Vector3.new(0, B / 2 - 0.5, 0))
	spawnPad.Parent = world.extras

	-- NPC
	for _, npc in markers.npc or {} do
		local mode = Config.Modes[npc.mode]
		if mode then
			local feet = world:cellCenter(npc.x, npc.y, npc.z) - Vector3.new(0, B / 2, 0)
			local model, prompt = Npc.create(world.extras, feet, LobbyService.spawn.Position, {
				name = mode.name,
				subtitle = "F 키로 참가",
				shirt = mode.color,
				pants = Color3.fromRGB(40, 40, 50),
				actionText = "참가하기",
				objectText = mode.name,
			})
			stations[npc.mode] = model
			prompt.Triggered:Connect(function(player)
				onJoin(player, npc.mode)
			end)
		end
	end

	-- 포털: 문틀 안의 빛나는 면, 들어가면 바로 참가
	local cooldown: { [Player]: number } = {}
	for _, p in markers.portal or {} do
		local mode = Config.Modes[p.mode]
		if mode then
			local center = world:cellCenter(p.x, p.y, p.z) + Vector3.new(0, (p.h - 1) / 2 * B, 0)
			local surface = Instance.new("Part")
			surface.Name = "Portal_" .. p.mode
			surface.Anchored = true
			surface.CanCollide = false
			surface.CanQuery = false
			surface.Material = Enum.Material.Neon
			surface.Color = mode.color
			surface.Transparency = 0.35
			surface.Size = Vector3.new(p.w * B, p.h * B, 0.6)
			surface.CFrame = CFrame.new(center)
			surface.Parent = world.extras
			local sparkle = Instance.new("ParticleEmitter")
			sparkle.Rate = 8
			sparkle.Lifetime = NumberRange.new(1, 2)
			sparkle.Speed = NumberRange.new(1, 3)
			sparkle.SpreadAngle = Vector2.new(180, 180)
			sparkle.LightEmission = 1
			sparkle.Color = ColorSequence.new(mode.color)
			sparkle.Size = NumberSequence.new(0.3)
			sparkle.Parent = surface
			local light = Instance.new("PointLight")
			light.Color = mode.color
			light.Range = 14
			light.Brightness = 1.5
			light.Parent = surface
			surface.Touched:Connect(function(hit)
				local player = playerFromHit(hit)
				if not player then
					return
				end
				local now = os.clock()
				if cooldown[player] and now - cooldown[player] < 4 then
					return
				end
				cooldown[player] = now
				onJoin(player, p.mode)
			end)
		end
	end
	Players.PlayerRemoving:Connect(function(player)
		cooldown[player] = nil
	end)

	-- 포털 위 안내판
	for _, label in markers.portalLabel or {} do
		local mode = Config.Modes[label.mode]
		if mode then
			local lines = hologram(world.extras, world:cellCenter(label.x, label.y, label.z), Vector2.new(16, 5.5), {
				{ text = mode.name, color = mode.color, scale = 1.4 },
				{ text = mode.description, color = Color3.fromRGB(235, 235, 235), scale = 0.7 },
				{ text = "", color = Color3.fromRGB(255, 255, 85), scale = 0.8 },
				{ text = "포털에 들어가면 참가!", color = Color3.fromRGB(85, 255, 85), scale = 0.7 },
			})
			portalInfo[label.mode] = lines[3]
		end
	end

	-- 큰 제목, 환영 문구, 순위, 파쿠르 안내
	local t = markers.title[1]
	hologram(world.extras, world:cellCenter(t.x, t.y, t.z), Vector2.new(28, 7), {
		{ text = Config.GameName, color = Color3.fromRGB(255, 220, 60), scale = 1.6 },
		{ text = "베드워즈 · 스카이워즈 · TNT 런", color = Color3.fromRGB(255, 255, 255), scale = 0.8 },
	})
	local w = markers.welcome and markers.welcome[1]
	if w then
		hologram(world.extras, world:cellCenter(w.x, w.y, w.z), Vector2.new(16, 4), {
			{ text = "어서 오세요!", color = Color3.fromRGB(85, 255, 255), scale = 1.2 },
			{ text = "북쪽 포털에 들어가거나 NPC에게 F", color = Color3.fromRGB(255, 255, 255), scale = 0.7 },
			{ text = "나침반을 우클릭해도 게임을 고를 수 있어요", color = Color3.fromRGB(200, 200, 200), scale = 0.7 },
		})
	end
	local board = markers.leaderboard and markers.leaderboard[1]
	if board then
		local rows = { { text = "승리 순위", color = Color3.fromRGB(255, 220, 60), scale = 1.3 } }
		for _ = 1, 10 do
			table.insert(rows, { text = "", color = Color3.fromRGB(240, 240, 240), scale = 0.6 })
		end
		local labels = hologram(world.extras, world:cellCenter(board.x, board.y, board.z), Vector2.new(11, 9), rows)
		for i = 2, #labels do
			table.insert(leaderboardLines, labels[i])
		end
	end
	local ps = markers.parkourStart and markers.parkourStart[1]
	if ps then
		hologram(world.extras, world:cellCenter(ps.x, ps.y, ps.z), Vector2.new(10, 3), {
			{ text = "파쿠르", color = Color3.fromRGB(85, 255, 85), scale = 1.2 },
			{ text = "탑 꼭대기까지 올라가 보세요", color = Color3.fromRGB(255, 255, 255), scale = 0.7 },
		})
	end

	local finish = markers.parkourEnd and markers.parkourEnd[1]
	if finish then
		local trigger = Instance.new("Part")
		trigger.Name = "ParkourFinish"
		trigger.Anchored = true
		trigger.CanCollide = false
		trigger.CanQuery = false
		trigger.Transparency = 1
		trigger.Size = Vector3.new(B * 3, 2, B * 3)
		trigger.CFrame = CFrame.new(world:cellCenter(finish.x, finish.y, finish.z) - Vector3.new(0, 0.5, 0))
		trigger.Parent = world.extras
		local done: { [Player]: boolean } = {}
		trigger.Touched:Connect(function(hit)
			local player = playerFromHit(hit)
			if player and not done[player] then
				done[player] = true
				Session.chat({ player }, '<font color="#55FF55">로비 파쿠르를 완주했습니다! 축하해요!</font>')
				Session.title({ player }, '<font color="#FFFF55">파쿠르 완주!</font>', "", 2)
			end
		end)
		Players.PlayerRemoving:Connect(function(player)
			done[player] = nil
		end)
	end

	LobbyService.world = world
	LobbyService.updateLeaderboard()
	return world
end

function LobbyService.updateStations(info: { [string]: { waiting: number, playing: number } })
	for modeId, entry in info do
		local text = string.format("대기 %d명 · 게임 중 %d명", entry.waiting, entry.playing)
		local model = stations[modeId]
		if model then
			Npc.setSubtitle(model, text)
		end
		local label = portalInfo[modeId]
		if label then
			label.Text = text
		end
	end
end

-- 지금 접속한 사람들의 승리 순위
function LobbyService.updateLeaderboard()
	if #leaderboardLines == 0 then
		return
	end
	local rows = {}
	for player, state in Session.all() do
		table.insert(rows, { name = player.DisplayName, wins = state.stats.wins })
	end
	table.sort(rows, function(a, b)
		if a.wins ~= b.wins then
			return a.wins > b.wins
		end
		return a.name < b.name
	end)
	local medals = { "#FFD700", "#D0D0D0", "#CD7F32" }
	for i, label in leaderboardLines do
		local row = rows[i]
		if row then
			local color = medals[i] or "#FFFFFF"
			label.Text = string.format('<font color="%s">%d.</font> %s - <font color="#55FF55">%d승</font>', color, i, row.name, row.wins)
		else
			label.Text = '<font color="#777777">-</font>'
		end
	end
end

task.spawn(function()
	while true do
		task.wait(5)
		LobbyService.updateLeaderboard()
	end
end)

return LobbyService
