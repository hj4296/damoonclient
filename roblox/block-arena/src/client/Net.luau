-- 클라이언트 통신: 서버 메시지를 종류별로 나눠 전달하고, 요청을 보냅니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Net = {}

local remotes = ReplicatedStorage:WaitForChild("Remotes")
local action = remotes:WaitForChild("Action") :: RemoteEvent
local client = remotes:WaitForChild("Client") :: RemoteEvent

local handlers: { [string]: { (...any) -> () } } = {}

function Net.on(kind: string, handler: (...any) -> ())
	handlers[kind] = handlers[kind] or {}
	table.insert(handlers[kind], handler)
end

function Net.send(kind: string, ...: any)
	action:FireServer(kind, ...)
end

client.OnClientEvent:Connect(function(kind: string, ...: any)
	local list = handlers[kind]
	if not list then
		return
	end
	for _, handler in list do
		local ok, err = pcall(handler, ...)
		if not ok then
			warn("[블록 아레나] 클라이언트 처리 오류 (" .. kind .. "):", err)
		end
	end
end)

return Net
