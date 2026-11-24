-- 움직임: 달리기(Ctrl), 웅크리기(Shift, 가장자리에서 안 떨어짐), 더블 점프(TNT 런), 낙하 피해, 넉백,
-- 물약 효과(신속/도약/느림), 사다리 타기, 물에서 헤엄치기(물에 떨어지면 낙하 피해 없음)
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

-- 서버가 적어 둔 효과 끝나는 시각(FX_이름)이 아직 안 지났는지
local function effect(name: string): boolean
	local ends = player:GetAttribute("FX_" .. name)
	return type(ends) == "number" and ends > workspace:GetServerTimeNow()
end
Movement.effect = effect

local overlap = OverlapParams.new()
overlap.FilterType = Enum.RaycastFilterType.Exclude

-- 몸이 닿은 블록 중 사다리/물/용암이 있는지
local function touching(root: BasePart, char: Model): (boolean, boolean, boolean)
	overlap.FilterDescendantsInstances = { char }
	local parts = workspace:GetPartBoundsInBox(root.CFrame * CFrame.new(0, -0.6, 0), Vector3.new(2.4, 4.6, 2.4), overlap)
	local ladder, water, lava = false, false, false
	for _, part in parts do
		local folder = part.Parent
		if folder and folder.Name == "Blocks" then
			local name = part.Name
			if name == "ladder" then
				ladder = true
			elseif name == "water" or name == "water_source" then
				water = true
			elseif name == "lava_flow" then
				lava = true
			end
		end
	end
	return ladder, water, lava
end

local function jumpHeld(humanoid: Humanoid): boolean
	return UserInputService:IsKeyDown(Enum.KeyCode.Space) or humanoid.Jump
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
		local speed
		if sneak then
			speed = Config.Movement.Sneak
		elseif sprinting then
			speed = Config.Movement.Sprint
		else
			speed = Config.Movement.Walk
		end
		if effect("speed") then
			speed *= Config.Effects.speed.walk
		end
		if effect("slow") then
			speed *= Config.Effects.slow.walk
		end
		local char0 = player.Character :: Model
		local onLadder, inWater, inLava = touching(root, char0)
		if inWater then
			speed *= 0.6
		elseif inLava then
			speed *= 0.5
		end
		humanoid.WalkSpeed = speed
		humanoid.JumpHeight = Config.Movement.JumpHeight * (if effect("jump") then Config.Effects.jump.jump else 1)

		-- 사다리: 앞으로 가거나 점프하면 올라가고, 웅크리면 멈춤, 가만히 있으면 천천히 내려감
		local velocity = root.AssemblyLinearVelocity
		if onLadder and State.context.location == "match" then
			local vy
			if humanoid.MoveDirection.Magnitude > 0.1 or jumpHeld(humanoid) then
				vy = 9
			elseif sneak then
				vy = 0
			else
				vy = math.max(velocity.Y, -5)
			end
			root.AssemblyLinearVelocity = Vector3.new(velocity.X, vy, velocity.Z)
			peakY = nil
		elseif inWater or inLava then
			-- 헤엄치기: 점프를 누르고 있으면 떠오르고, 아니면 천천히 가라앉음
			local vy = if jumpHeld(humanoid) then 8 else math.max(velocity.Y, -6)
			root.AssemblyLinearVelocity = Vector3.new(velocity.X, vy, velocity.Z)
			peakY = nil
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
