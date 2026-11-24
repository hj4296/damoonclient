-- 클라이언트 시작점: 화면을 만들고 서버 메시지를 화면에 연결합니다.
local Players = game:GetService("Players")
local StarterGui = game:GetService("StarterGui")

local Net = require(script.Parent.Net)
local State = require(script.Parent.State)
local Hud = require(script.Parent.UI.Hud)
local Windows = require(script.Parent.UI.Windows)
local Controls = require(script.Parent.Controls)
local Movement = require(script.Parent.Movement)
local Effects = require(script.Parent.Effects)
local BlockTextures = require(script.Parent.BlockTextures)

local player = Players.LocalPlayer

-- 로블록스 기본 체력바와 가방 숨기기 (마인크래프트식 화면을 대신 씀)
task.spawn(function()
	for _ = 1, 20 do
		local ok = pcall(function()
			StarterGui:SetCoreGuiEnabled(Enum.CoreGuiType.Health, false)
			StarterGui:SetCoreGuiEnabled(Enum.CoreGuiType.Backpack, false)
		end)
		if ok then
			break
		end
		task.wait(0.5)
	end
end)

BlockTextures.load() -- 핫바 아이콘이 블록 텍스처를 쓰므로 화면보다 먼저
Hud.init(player:WaitForChild("PlayerGui"))
Windows.init(Hud.gui())
Controls.init()
Movement.init()
Effects.init()
task.spawn(BlockTextures.init)

local HINTS = {
	lobby = "F: NPC  ·  우클릭: 아이템 사용  ·  V: 시점  ·  1~9: 핫바",
	waiting = "우클릭: 맵 투표 / 나가기  ·  L 두 번: 로비로  ·  V: 시점",
	match = "E: 가방  ·  Q: 버리기  ·  Shift: 웅크리기  ·  Ctrl: 달리기  ·  V: 시점  ·  L 두 번: 로비로",
	respawn = "곧 부활합니다...",
	spectate = "관전 중  ·  L 두 번: 로비로",
}

local function refreshHealth()
	local char = player.Character
	local humanoid = char and char:FindFirstChildOfClass("Humanoid")
	if humanoid then
		local absorption = player:GetAttribute("Absorption")
		Hud.setHealth(humanoid.Health, humanoid.MaxHealth, if type(absorption) == "number" then absorption else 0)
	end
end

local function onCharacter(char: Model)
	local humanoid = char:WaitForChild("Humanoid", 10) :: Humanoid?
	if humanoid then
		humanoid.HealthChanged:Connect(refreshHealth)
		humanoid:GetPropertyChangedSignal("MaxHealth"):Connect(refreshHealth)
		refreshHealth()
	end
end
if player.Character then
	task.spawn(onCharacter, player.Character)
end
player.CharacterAdded:Connect(onCharacter)
player:GetAttributeChangedSignal("Absorption"):Connect(refreshHealth)

Net.on("inv", function(data)
	State.applyInventory(data)
end)

Net.on("context", function(context)
	local previous = State.context.location
	State.context = context or { location = "lobby" }
	if previous ~= State.context.location and Windows.isOpen() then
		Windows.close()
	end
	Hud.setHint(HINTS[State.context.location] or "")
	Hud.setStatusVisible(State.context.location == "match" and State.context.mode ~= "tntrun")
	State.emit("context")
end)

Net.on("sidebar", function(data)
	Hud.setSidebar(data)
end)

Net.on("title", function(title, subtitle, duration)
	Hud.title(title, subtitle, duration)
end)

Net.on("chat", function(text)
	Hud.chat(text)
end)

Net.on("vote", function(vote)
	State.vote = vote
	if Windows.kind() == "vote" then
		Windows.refresh()
	end
end)

Net.on("openVote", function()
	Windows.openVote()
end)

Net.on("lobbyInfo", function(info)
	State.lobbyInfo = info or {}
	if Windows.kind() == "games" then
		Windows.refresh()
	end
end)

Net.on("openGames", function(info)
	State.lobbyInfo = info or {}
	Windows.openGames()
end)

Net.on("openShop", function(kind, shop)
	Windows.openShop(kind, shop)
end)

Net.on("shopState", function(shop)
	Windows.updateShop(shop)
end)

Net.on("openChest", function(id, title, contents)
	Windows.openChest(id, title, contents)
end)

Net.on("chest", function(id, contents)
	Windows.updateChest(id, contents)
end)

Net.on("closeChest", function()
	if Windows.kind() == "chest" then
		Windows.close()
	end
end)

Net.on("crit", function()
	Hud.actionbar('<font color="#FFAA00">치명타!</font>', 0.8)
end)

Hud.setHint(HINTS.lobby)
Hud.setStatusVisible(false)
