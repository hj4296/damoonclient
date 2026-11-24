-- 서버 <-> 클라이언트 통신. RemoteEvent 두 개만 씁니다.
--  Action : 클라이언트 -> 서버 (종류 문자열 + 인자)
--  Client : 서버 -> 클라이언트 (종류 문자열 + 인자)
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local Players = game:GetService("Players")

local Net = {}

local folder = ReplicatedStorage:FindFirstChild("Remotes")
if not folder then
	folder = Instance.new("Folder")
	folder.Name = "Remotes"
	folder.Parent = ReplicatedStorage
end

local function remote(name: string): RemoteEvent
	local existing = folder:FindFirstChild(name)
	if existing and existing:IsA("RemoteEvent") then
		return existing
	end
	local event = Instance.new("RemoteEvent")
	event.Name = name
	event.Parent = folder
	return event
end

Net.Action = remote("Action")
Net.Client = remote("Client")

local handlers: { [string]: (Player, ...any) -> () } = {}
local buckets: { [Player]: { tokens: number, last: number } } = {}

local RATE = 45 -- 초당 허용 요청 수
local BURST = 90

function Net.on(kind: string, handler: (Player, ...any) -> ())
	handlers[kind] = handler
end

function Net.send(player: Player, kind: string, ...: any)
	if player.Parent then
		Net.Client:FireClient(player, kind, ...)
	end
end

function Net.sendMany(players: { Player }, kind: string, ...: any)
	for _, player in players do
		Net.send(player, kind, ...)
	end
end

function Net.broadcast(kind: string, ...: any)
	Net.Client:FireAllClients(kind, ...)
end

local function allow(player: Player): boolean
	local now = os.clock()
	local bucket = buckets[player]
	if not bucket then
		bucket = { tokens = BURST, last = now }
		buckets[player] = bucket
	end
	bucket.tokens = math.min(BURST, bucket.tokens + (now - bucket.last) * RATE)
	bucket.last = now
	if bucket.tokens < 1 then
		return false
	end
	bucket.tokens -= 1
	return true
end

Net.Action.OnServerEvent:Connect(function(player: Player, kind: any, ...: any)
	if type(kind) ~= "string" or not allow(player) then
		return
	end
	local handler = handlers[kind]
	if not handler then
		return
	end
	local ok, err = pcall(handler, player, ...)
	if not ok then
		warn(string.format("[블록 아레나] %s 처리 중 오류 (%s): %s", kind, player.Name, tostring(err)))
	end
end)

Players.PlayerRemoving:Connect(function(player)
	buckets[player] = nil
end)

return Net
