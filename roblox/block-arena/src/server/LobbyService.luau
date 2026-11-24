-- 로비 만들기: 광장 블록, 스폰 위치, 미니게임 NPC, 제목 홀로그램, 파쿠르 완주 지점
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
LobbyService.voidY = Config.LobbyOrigin.Y - 40

local stations: { [string]: Model } = {}

local function hologram(parent: Instance, position: Vector3)
	local anchor = Instance.new("Part")
	anchor.Name = "Title"
	anchor.Anchored = true
	anchor.CanCollide = false
	anchor.CanQuery = false
	anchor.CanTouch = false
	anchor.Transparency = 1
	anchor.Size = Vector3.new(1, 1, 1)
	anchor.CFrame = CFrame.new(position)
	anchor.Parent = parent
	local gui = Instance.new("BillboardGui")
	gui.Size = UDim2.new(24, 0, 6, 0)
	gui.LightInfluence = 0
	gui.MaxDistance = 200
	gui.Parent = anchor
	local title = Instance.new("TextLabel")
	title.BackgroundTransparency = 1
	title.Size = UDim2.new(1, 0, 0.62, 0)
	title.Font = Enum.Font.Arcade
	title.TextScaled = true
	title.TextColor3 = Color3.fromRGB(255, 220, 60)
	title.TextStrokeColor3 = Color3.fromRGB(120, 60, 0)
	title.TextStrokeTransparency = 0
	title.Text = Config.GameName
	title.Parent = gui
	local sub = Instance.new("TextLabel")
	sub.BackgroundTransparency = 1
	sub.Position = UDim2.new(0, 0, 0.62, 0)
	sub.Size = UDim2.new(1, 0, 0.38, 0)
	sub.Font = Enum.Font.Arcade
	sub.TextScaled = true
	sub.TextColor3 = Color3.fromRGB(255, 255, 255)
	sub.TextStrokeTransparency = 0.4
	sub.Text = "베드워즈 · 스카이워즈 · TNT 런"
	sub.Parent = gui
end

function LobbyService.build(onJoin: (Player, string) -> ())
	local world = World.new(Config.LobbyOrigin, "Lobby")
	local markers = {}
	LobbyMap.build(world:builder(markers), Util.rng(42))

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

	local t = markers.title[1]
	hologram(world.extras, world:cellCenter(t.x, t.y, t.z))

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
			local model = hit:FindFirstAncestorOfClass("Model")
			local player = model and Players:GetPlayerFromCharacter(model)
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

	world:show(workspace)
	LobbyService.world = world
	return world
end

function LobbyService.updateStations(info: { [string]: { waiting: number, playing: number } })
	for modeId, model in stations do
		local entry = info[modeId]
		if entry then
			Npc.setSubtitle(model, string.format("대기 %d명 · 게임 중 %d명", entry.waiting, entry.playing))
		end
	end
end

return LobbyService
