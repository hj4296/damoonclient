-- 움직임: 달리기(Ctrl), 웅크리기(Shift, 가장자리에서 안 떨어짐), 더블 점프(TNT 런), 낙하 피해, 넉백
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local UserInputService = game:GetService("UserInputService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Net = require(script.Parent.Net)
local State = require(script.Parent.State)

local player = Players.LocalPlayer
local B = Config.BlockSize

local Movement = {}

local sprinting = false
local airborneSince: number? = nil
local doubleJumped = false
local peakY: number? = nil
local lastSafe: CFrame? = nil
local lastPosition: Vector3? = nil

local function parts(): (Humanoid?, BasePart?)
	local char = player.Character
	if not char then
		return nil, nil
	end
	local humanoid = char:FindFirstChildOfClass("Humanoid")
	local root = char:FindFirstChild("HumanoidRootPart")
	if humanoid and root and root:IsA("BasePart") then
		return humanoid, root
	end
	return nil, nil
end

local function sneaking(): boolean
	return UserInputService:IsKeyDown(Enum.KeyCode.LeftShift) or player:GetAttribute("SneakToggle") == true
end

local function groundBelow(position: Vector3, char: Model, depth: number): boolean
	local params = RaycastParams.new()
	params.FilterType = Enum.RaycastFilterType.Exclude
	params.FilterDescendantsInstances = { char }
	local result = workspace:Raycast(position, Vector3.new(0, -depth, 0), params)
	return result ~= nil and result.Instance.CanCollide
end

local function onCharacter(char: Model)
	sprinting = false
	airborneSince = nil
	doubleJumped = false
	peakY = nil
	lastSafe = nil
	local humanoid = char:WaitForChild("Humanoid", 10) :: Humanoid?
	if not humanoid then
		return
	end
	humanoid.StateChanged:Connect(function(_, new)
		local _, root = parts()
		if not root then
			return
		end
		if new == Enum.HumanoidStateType.Freefall or new == Enum.HumanoidStateType.Jumping then
			airborneSince = airborneSince or os.clock()
			peakY = math.max(peakY or root.Position.Y, root.Position.Y)
		elseif new == Enum.HumanoidStateType.Landed or new == Enum.HumanoidStateType.Running then
			if peakY then
				local fall = (peakY - root.Position.Y) / B
				if fall > Config.Combat.FallSafeBlocks and State.context.location == "match" then
					Net.send("fall", fall)
				end
			end
			peakY = nil
			airborneSince = nil
			doubleJumped = false
		end
	end)
end

function Movement.init()
	if player.Character then
		task.spawn(onCharacter, player.Character)
	end
	player.CharacterAdded:Connect(onCharacter)

	UserInputService.InputBegan:Connect(function(input, processed)
		if processed then
			return
		end
		if input.KeyCode == Enum.KeyCode.LeftControl then
			sprinting = true
		end
	end)

	-- 공중에서 점프를 한 번 더 누르면 더블 점프
	UserInputService.JumpRequest:Connect(function()
		local humanoid, root = parts()
		if not humanoid or not root or not State.context.doubleJump or doubleJumped then
			return
		end
		local left = player:GetAttribute("DoubleJumps")
		if type(left) ~= "number" or left <= 0 then
			return
		end
		if humanoid.FloorMaterial ~= Enum.Material.Air or not airborneSince or os.clock() - airborneSince < 0.15 then
			return
		end
		doubleJumped = true
		local look = workspace.CurrentCamera.CFrame.LookVector
		local flat = Vector3.new(look.X, 0, look.Z)
		local boost = if flat.Magnitude > 0.1 then flat.Unit * 26 else Vector3.zero
		root.AssemblyLinearVelocity = Vector3.new(boost.X, Config.TntRun.DoubleJumpPower, boost.Z)
		Net.send("doubleJump")
	end)

	Net.on("kb", function(velocity: Vector3)
		local _, root = parts()
		if root and typeof(velocity) == "Vector3" then
			root.AssemblyLinearVelocity = velocity
			peakY = nil
		end
	end)

	RunService.Heartbeat:Connect(function()
		local humanoid, root = parts()
		if not humanoid or not root or humanoid.Health <= 0 then
			return
		end
		local sneak = sneaking()
		if humanoid.MoveDirection.Magnitude < 0.1 then
			sprinting = false
		end
		if sneak then
			humanoid.WalkSpeed = Config.Movement.Sneak
		elseif sprinting then
			humanoid.WalkSpeed = Config.Movement.Sprint
		else
			humanoid.WalkSpeed = Config.Movement.Walk
		end
		-- 순간이동(진주, 서버 이동)은 낙하로 치지 않음
		if lastPosition and (root.Position - lastPosition).Magnitude > 25 then
			peakY = if peakY then root.Position.Y else nil
		end
		lastPosition = root.Position
		if peakY then
			peakY = math.max(peakY, root.Position.Y)
		end

		-- 웅크리면 블록 가장자리에서 떨어지지 않게 막기
		local char = player.Character :: Model
		local grounded = humanoid.FloorMaterial ~= Enum.Material.Air
		if sneak and State.context.location == "match" then
			local leg = if humanoid.RigType == Enum.HumanoidRigType.R6 then 2 else humanoid.HipHeight
			local depth = leg + root.Size.Y / 2 + 1.2
			if grounded and groundBelow(root.Position, char, depth) then
				lastSafe = root.CFrame
			elseif lastSafe and not grounded and root.AssemblyLinearVelocity.Y <= 0 then
				-- 방금 가장자리를 넘었으면 마지막 안전한 곳으로 되돌림
				if (lastSafe.Position - root.Position).Magnitude < 3 then
					root.CFrame = CFrame.new(lastSafe.Position) * root.CFrame.Rotation
					root.AssemblyLinearVelocity = Vector3.zero
				end
				lastSafe = nil
			end
		else
			lastSafe = nil
		end
	end)
end

return Movement
