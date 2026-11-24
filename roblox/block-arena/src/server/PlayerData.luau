-- 승리/처치 기록 저장 (DataStore). 스튜디오에서 API 접근을 꺼 두었으면 저장 없이 그냥 진행합니다.
local DataStoreService = game:GetService("DataStoreService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Config = require(ReplicatedStorage.Shared.Config)
local Session = require(script.Parent.Session)

local PlayerData = {}

local store: DataStore? = nil
local disabled = false
local loadFailed: { [Player]: boolean } = {}

do
	local ok, result = pcall(function()
		return DataStoreService:GetDataStore(Config.DataStoreName)
	end)
	if ok then
		store = result
	else
		disabled = true
		warn("[블록 아레나] 저장소를 쓸 수 없어 기록이 저장되지 않습니다:", result)
	end
end

local function disableIfStudio(err: any)
	local text = tostring(err)
	if string.find(text, "Studio") or string.find(text, "publish") or string.find(text, "403") then
		if not disabled then
			warn("[블록 아레나] 스튜디오 저장 꺼짐: 게임 설정 > 보안 > 'API 서비스에 대한 스튜디오 접근 허용'을 켜면 저장됩니다.")
		end
		disabled = true
	end
end

local function key(player: Player): string
	return "p_" .. player.UserId
end

local function makeLeaderstats(player: Player)
	local folder = Instance.new("Folder")
	folder.Name = "leaderstats"
	local wins = Instance.new("IntValue")
	wins.Name = "승리"
	wins.Parent = folder
	local kills = Instance.new("IntValue")
	kills.Name = "처치"
	kills.Parent = folder
	folder.Parent = player
end

local function refresh(player: Player)
	local state = Session.get(player)
	local folder = player:FindFirstChild("leaderstats")
	if not state or not folder then
		return
	end
	local wins = folder:FindFirstChild("승리")
	local kills = folder:FindFirstChild("처치")
	if wins and wins:IsA("IntValue") then
		wins.Value = state.stats.wins
	end
	if kills and kills:IsA("IntValue") then
		kills.Value = state.stats.kills
	end
end

function PlayerData.load(player: Player)
	makeLeaderstats(player)
	local state = Session.get(player)
	if not state or disabled or not store then
		return
	end
	local ok, data = pcall(function()
		return (store :: DataStore):GetAsync(key(player))
	end)
	if not ok then
		loadFailed[player] = true
		disableIfStudio(data)
		warn("[블록 아레나] 기록을 불러오지 못했습니다:", data)
		return
	end
	if type(data) == "table" then
		state.stats.wins = tonumber(data.wins) or 0
		state.stats.kills = tonumber(data.kills) or 0
	end
	refresh(player)
end

function PlayerData.save(player: Player)
	local state = Session.get(player)
	if not state or disabled or not store or loadFailed[player] then
		return
	end
	local payload = { wins = state.stats.wins, kills = state.stats.kills }
	local ok, err = pcall(function()
		(store :: DataStore):SetAsync(key(player), payload)
	end)
	if not ok then
		disableIfStudio(err)
		warn("[블록 아레나] 기록 저장 실패:", err)
	end
end

function PlayerData.forget(player: Player)
	loadFailed[player] = nil
end

function PlayerData.addWin(player: Player)
	local state = Session.get(player)
	if state then
		state.stats.wins += 1
		refresh(player)
	end
end

function PlayerData.addKill(player: Player)
	local state = Session.get(player)
	if state then
		state.stats.kills += 1
		refresh(player)
	end
end

return PlayerData
