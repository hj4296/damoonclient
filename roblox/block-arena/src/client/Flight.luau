-- VIP 로비 날기: 스페이스를 빠르게 두 번 누르면 날기 켜기/끄기.
-- 날 때: WASD 로 이동, 스페이스로 위, Shift(또는 Ctrl)로 아래. 모바일은 설정 탭의 날기 버튼과 점프 버튼.
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local UserInputService = game:GetService("UserInputService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Net = require(script.Parent.Net)
local State = require(script.Parent.State)

local player = Players.LocalPlayer

local Flight = {}

local flying = false
local lastSpace = 0
local attachment: Attachment? = nil
local velocity: LinearVelocity? = nil
local align: AlignOrientation? = nil
local controls: any = nil

Flight.onChanged = function(_flying: boolean) end

local function parts(): (Humanoid?, BasePart?)
	local char = player.Character
	local humanoid = char and char:FindFirstChildOfClass("Humanoid")
	local root = char and char:FindFirstChild("HumanoidRootPart")
	if humanoid and root and root:IsA("BasePart") then
		return humanoid, root
	end
	return nil, nil
end

function Flight.allowed(): boolean
	return player:GetAttribute("VIP") == true and State.context.location == "lobby" and State.settings.fly ~= false
end

function Flight.isFlying(): boolean
	return flying
end

function Flight.stop()
	if not flying then
		return
	end
	flying = false
	for _, thing in { velocity, align, attachment } do
		if thing then
			thing:Destroy()
		end
	end
	velocity, align, attachment = nil, nil, nil
	local humanoid = parts()
	if humanoid then
		humanoid.PlatformStand = false
	end
	Net.send("flying", false)
	Flight.onChanged(false)
end

function Flight.start()
	if flying or not Flight.allowed() then
		return
	end
	local humanoid, root = parts()
	if not humanoid or not root or humanoid.Health <= 0 then
		return
	end
	flying = true
	local a = Instance.new("Attachment")
	a.Name = "FlyAttachment"
	a.Parent = root
	local v = Instance.new("LinearVelocity")
	v.Name = "FlyVelocity"
	v.Attachment0 = a
	v.MaxForce = 1e7
	v.VelocityConstraintMode = Enum.VelocityConstraintMode.Vector
	v.RelativeTo = Enum.ActuatorRelativeTo.World
	v.VectorVelocity = Vector3.zero
	v.Parent = root
	local o = Instance.new("AlignOrientation")
	o.Name = "FlyAlign"
	o.Mode = Enum.OrientationAlignmentMode.OneAttachment
	o.Attachment0 = a
	o.MaxTorque = 1e7
	o.Responsiveness = 30
	o.CFrame = CFrame.Angles(0, select(2, root.CFrame:ToEulerAnglesYXZ()), 0)
	o.Parent = root
	attachment, velocity, align = a, v, o
	humanoid.PlatformStand = true
	Net.send("flying", true)
	Flight.onChanged(true)
end

function Flight.toggle()
	if flying then
		Flight.stop()
	else
		Flight.start()
	end
end

-- 카메라 기준 이동 방향 (키보드, 패드, 모바일 조이스틱 모두)
local function moveDirection(humanoid: Humanoid): Vector3
	if controls == nil then
		local ok, result = pcall(function()
			local module = player:WaitForChild("PlayerScripts"):WaitForChild("PlayerModule", 5)
			return if module then (require(module :: any) :: any):GetControls() else false
		end)
		controls = if ok and result then result else false
	end
	if controls then
		local ok, mv = pcall(function()
			return controls:GetMoveVector()
		end)
		if ok and typeof(mv) == "Vector3" and mv.Magnitude > 0.01 then
			local cam = workspace.CurrentCamera.CFrame
			local look = Vector3.new(cam.LookVector.X, 0, cam.LookVector.Z)
			local right = Vector3.new(cam.RightVector.X, 0, cam.RightVector.Z)
			if look.Magnitude > 0.01 then
				look = look.Unit
			end
			if right.Magnitude > 0.01 then
				right = right.Unit
			end
			local dir = right * mv.X - look * mv.Z
			return if dir.Magnitude > 1 then dir.Unit else dir
		end
	end
	return humanoid.MoveDirection
end

function Flight.init()
	UserInputService.InputBegan:Connect(function(input, processed)
		if processed or input.KeyCode ~= Enum.KeyCode.Space then
			return
		end
		local now = os.clock()
		if now - lastSpace < 0.32 then
			lastSpace = 0
			if Flight.allowed() or flying then
				Flight.toggle()
			end
		else
			lastSpace = now
		end
	end)

	player.CharacterAdded:Connect(function()
		flying = false
		velocity, align, attachment = nil, nil, nil
		Flight.onChanged(false)
	end)

	RunService.Heartbeat:Connect(function()
		if not flying then
			return
		end
		local humanoid, root = parts()
		if not humanoid or not root or not velocity or not align or humanoid.Health <= 0 or not Flight.allowed() then
			Flight.stop()
			return
		end
		local horizontal = moveDirection(humanoid) * Config.Fly.Speed
		local up = 0
		if UserInputService:IsKeyDown(Enum.KeyCode.Space) or humanoid.Jump then
			up += Config.Fly.Vertical
		end
		if UserInputService:IsKeyDown(Enum.KeyCode.LeftShift) or UserInputService:IsKeyDown(Enum.KeyCode.LeftControl) then
			up -= Config.Fly.Vertical
		end
		local ceiling = Config.LobbyOrigin.Y + Config.Fly.MaxHeight
		if root.Position.Y > ceiling and up > 0 then
			up = 0
		end
		velocity.VectorVelocity = horizontal + Vector3.new(0, up, 0)
		local cam = workspace.CurrentCamera.CFrame
		local yaw = math.atan2(-cam.LookVector.X, -cam.LookVector.Z)
		align.CFrame = CFrame.Angles(0, yaw, 0)
	end)
end

return Flight
