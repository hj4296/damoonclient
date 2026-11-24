-- 서버 시작점: 로비를 만들고, 들어오는 플레이어를 로비로 보냅니다.
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local CollectionService = game:GetService("CollectionService")

local Config = require(ReplicatedStorage.Shared.Config)
local Session = require(script.Parent.Session)
local Combat = require(script.Parent.Combat)
local Matches = require(script.Parent.Matches)
local PlayerData = require(script.Parent.PlayerData)
local Projectiles = require(script.Parent.Projectiles)
local LobbyService = require(script.Parent.LobbyService)
require(script.Parent.Actions)

workspace.Gravity = Config.Movement.Gravity
Players.CharacterAutoLoads = false

-- 기본 템플릿(Baseplate)으로 연 경우 남아 있는 바닥/스폰 정리
for _, name in { "Baseplate", "SpawnLocation" } do
	local old = workspace:FindFirstChild(name)
	if old and old:IsA("BasePart") then
		old:Destroy()
	end
end

LobbyService.build(function(player, modeId)
	Matches.join(player, modeId)
end)
Matches.setLobby(LobbyService.spawn, LobbyService.voidY)
Matches.onLobbyInfo = LobbyService.updateStations

-- 용암 같은 Kill 태그 블록
local function bindKill(part: Instance)
	if not part:IsA("BasePart") then
		return
	end
	part.Touched:Connect(function(hit)
		local model = hit:FindFirstAncestorOfClass("Model")
		local player = model and Players:GetPlayerFromCharacter(model)
		local state = player and Session.get(player)
		if not player or not state or not state.alive then
			return
		end
		if state.location == "match" and state.match and state.match.state == "playing" then
			Combat.damage(player, 1000, { cause = "lava", ignoreArmor = true })
		else
			Session.teleport(player, if state.location == "lobby" then LobbyService.spawn else (state.match and state.match.spectatorSpawn or LobbyService.spawn))
		end
	end)
end
for _, part in CollectionService:GetTagged("Kill") do
	bindKill(part)
end
CollectionService:GetInstanceAddedSignal("Kill"):Connect(bindKill)

local function onPlayerAdded(player: Player)
	if Session.get(player) then
		return
	end
	Session.create(player)
	Session.attach(player)
	task.spawn(PlayerData.load, player)
	Matches.toLobby(player)
	Session.chat({ player }, string.format('<font color="#FFFF55">%s</font>에 오신 걸 환영합니다!', Config.GameName))
	Session.chat({ player }, "NPC 앞에서 F 키, 또는 나침반을 우클릭해서 미니게임을 고르세요.")
end

Players.PlayerAdded:Connect(onPlayerAdded)
for _, player in Players:GetPlayers() do
	task.spawn(onPlayerAdded, player)
end

Players.PlayerRemoving:Connect(function(player)
	Matches.playerRemoving(player)
	Projectiles.clearFor(player)
	PlayerData.save(player)
	PlayerData.forget(player)
	Session.remove(player)
end)

game:BindToClose(function()
	local pending = 0
	for _, player in Players:GetPlayers() do
		pending += 1
		task.spawn(function()
			PlayerData.save(player)
			pending -= 1
		end)
	end
	local started = os.clock()
	while pending > 0 and os.clock() - started < 20 do
		task.wait(0.2)
	end
end)

task.spawn(function()
	while true do
		task.wait(120)
		for _, player in Players:GetPlayers() do
			task.spawn(PlayerData.save, player)
		end
	end
end)
