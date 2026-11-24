-- 조작: 조준(블록/사람/몹), 왼쪽 클릭 공격·부수기, 오른쪽 클릭 설치·사용(물약/양동이/낚싯대), 숫자키 핫바, 단축키, 모바일 버튼
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local UserInputService = game:GetService("UserInputService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Items = require(Shared:WaitForChild("Items"))
local Rules = require(Shared:WaitForChild("Rules"))
local Net = require(script.Parent.Net)
local State = require(script.Parent.State)
local Hud = require(script.Parent.UI.Hud)
local Windows = require(script.Parent.UI.Windows)
local Theme = require(script.Parent.UI.Theme)
local CharacterVisuals = require(script.Parent.CharacterVisuals)

local player = Players.LocalPlayer

local Controls = {}
Controls.onCameraChanged = function(_thirdPerson: boolean) end

local thirdPerson = false
local attackHeld = false
local useHeld = false
local lastAttack = 0
local lastPlace = 0
local bowStart: number? = nil
local leaveArmed = 0
local breaking: { key: string, progress: number }? = nil
local breakCooldown = 0
local selection: SelectionBox

export type Target = {
	kind: string,
	player: Player?,
	mobId: number?,
	part: BasePart?,
	cell: { number }?,
	place: { number }?,
	normal: { number }?,
}
local target: Target? = nil

local function character(): (Model?, BasePart?)
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	if root and root:IsA("BasePart") then
		return char, root
	end
	return nil, nil
end

local function blockInfo(part: BasePart): (BasePart?, number, number, number)
	local block = part
	local parent = block.Parent
	if parent and parent:IsA("BasePart") then
		block = parent
	end
	local folder = block.Parent
	local model = folder and folder.Parent
	if not folder or folder.Name ~= "Blocks" or not model or not model:HasTag("BlockWorld") then
		return nil, 0, 0, 0
	end
	local origin = model:GetAttribute("Origin")
	local size = model:GetAttribute("BlockSize") or Config.BlockSize
	if typeof(origin) ~= "Vector3" then
		return nil, 0, 0, 0
	end
	local rel = (block.Position - origin) / size
	return block, math.round(rel.X), math.round(rel.Y), math.round(rel.Z)
end

local function playerFromPart(part: BasePart): Player?
	local model = part:FindFirstAncestorOfClass("Model")
	while model do
		if model:FindFirstChildOfClass("Humanoid") then
			return Players:GetPlayerFromCharacter(model)
		end
		model = model:FindFirstAncestorOfClass("Model")
	end
	return nil
end

-- 물/용암처럼 통과하는 블록은 조준할 때 건너뜀 (그 너머 블록에 놓거나 부술 수 있게)
local FLUIDS = { water = true, water_source = true, lava = true, lava_flow = true }

local function mobFromPart(part: BasePart): number?
	local id = part:GetAttribute("MobId")
	return if type(id) == "number" then id else nil
end

local function aimRay(): Ray
	local camera = workspace.CurrentCamera
	local size = camera.ViewportSize
	return camera:ViewportPointToRay(size.X / 2, size.Y / 2)
end

local function updateTarget()
	target = nil
	local char, root = character()
	if not char or not root then
		return
	end
	local ray = aimRay()
	local params = RaycastParams.new()
	params.FilterType = Enum.RaycastFilterType.Exclude
	local exclude: { Instance } = { char }
	local projectiles = workspace:FindFirstChild("Projectiles")
	if projectiles then
		table.insert(exclude, projectiles)
	end
	params.FilterDescendantsInstances = exclude
	local result = workspace:Raycast(ray.Origin, ray.Direction * 60, params)
	for _ = 1, 4 do
		if result and (FLUIDS[result.Instance.Name] or FLUIDS[result.Instance:GetAttribute("BlockId") or ""]) then
			params:AddToFilter(result.Instance)
			result = workspace:Raycast(ray.Origin, ray.Direction * 60, params)
		else
			break
		end
	end
	if not result then
		return
	end
	local head = root.Position + Vector3.new(0, 1.5, 0)
	local mobId = mobFromPart(result.Instance)
	if mobId then
		local reach = Config.Reach.Attack + (if result.Instance.Parent and result.Instance.Parent:GetAttribute("MobKind") == "dragon" then 5 else 1)
		if (result.Position - head).Magnitude <= reach then
			target = { kind = "mob", mobId = mobId }
		end
		return
	end
	local other = playerFromPart(result.Instance)
	if other and other ~= player then
		local otherRoot = other.Character and other.Character:FindFirstChild("HumanoidRootPart")
		if otherRoot and otherRoot:IsA("BasePart") and (otherRoot.Position - root.Position).Magnitude <= Config.Reach.Attack then
			target = { kind = "player", player = other }
		end
		return
	end
	if (result.Position - head).Magnitude > Config.Reach.Block then
		return
	end
	local block, x, y, z = blockInfo(result.Instance)
	if not block then
		return
	end
	local n = result.Normal
	target = {
		kind = "block",
		part = block,
		cell = { x, y, z },
		place = { x + math.round(n.X), y + math.round(n.Y), z + math.round(n.Z) },
		normal = { math.round(n.X), math.round(n.Y), math.round(n.Z) },
	}
end

local function selectSlot(index: number)
	if index < 1 or index > 9 or index == State.selected then
		return
	end
	State.selected = index
	State.emit("inv")
	Hud.showItemName()
	Net.send("select", index)
	breaking = nil
	bowStart = nil
end

local function selectedDef()
	local stack = State.selectedStack()
	return if stack then Items.get(stack.id) else nil
end

local function canAct(): boolean
	return not Windows.isOpen()
end

local function tryAttack()
	if target and target.kind == "player" and target.player and State.context.pvp then
		lastAttack = os.clock()
		CharacterVisuals.swing(player)
		Net.send("attack", target.player)
		return true
	elseif target and target.kind == "mob" and target.mobId and State.context.pvp then
		lastAttack = os.clock()
		CharacterVisuals.swing(player)
		Net.send("attackMob", target.mobId)
		return true
	end
	return false
end

-- 블록처럼 조준한 칸에 놓는 아이템 (블록, 양동이, 팝업 타워, 골렘 알)
local PLACEABLE = { block = true, bucket = true, deploy = true }

local function tryPlace()
	local def = selectedDef()
	if not def or not PLACEABLE[def.kind] or not State.context.build then
		return false
	end
	if target and target.kind == "block" and target.place then
		local p = target.place
		local n = target.normal or { 0, 1, 0 }
		CharacterVisuals.swing(player)
		Net.send("place", p[1], p[2], p[3], n[1], n[2], n[3])
		lastPlace = os.clock()
		return true
	end
	return false
end

-- 광부의 손 강화와 피로 함정을 반영한 부수기 속도
local function breakSpeed(): number
	local haste = player:GetAttribute("Haste")
	local fatigue = player:GetAttribute("FX_fatigue")
	local fatigued = type(fatigue) == "number" and fatigue > workspace:GetServerTimeNow()
	return Rules.breakSpeed(if type(haste) == "number" then haste else 0, fatigued)
end

local function useStart()
	local def = selectedDef()
	if not def then
		return
	end
	if PLACEABLE[def.kind] then
		tryPlace()
	elseif def.kind == "bow" then
		bowStart = os.clock()
	elseif def.kind == "menu" or def.kind == "food" or def.kind == "armor" or def.kind == "throwable" or def.kind == "potion" or def.kind == "rod" then
		if def.kind == "rod" or def.kind == "throwable" then
			CharacterVisuals.swing(player)
		end
		Net.send("use", workspace.CurrentCamera.CFrame.LookVector)
	end
end

local function useEnd()
	if bowStart then
		local charge = math.clamp((os.clock() - bowStart) / 1, 0.1, 1)
		bowStart = nil
		Hud.actionbar("", nil)
		Net.send("use", workspace.CurrentCamera.CFrame.LookVector, charge)
	end
end

local function toggleInventory()
	if Windows.isOpen() then
		Windows.close()
	elseif State.context.location == "match" then
		Windows.openInventory()
	end
end

local function toggleCamera()
	thirdPerson = not thirdPerson
	if thirdPerson then
		player.CameraMode = Enum.CameraMode.Classic
		player.CameraMinZoomDistance = 12
		player.CameraMaxZoomDistance = 12
	else
		player.CameraMinZoomDistance = 0.5
		player.CameraMaxZoomDistance = 0.5
		player.CameraMode = Enum.CameraMode.LockFirstPerson
	end
	Controls.onCameraChanged(thirdPerson)
end

function Controls.setThirdPerson(third: boolean)
	if third ~= thirdPerson then
		toggleCamera()
	end
end

local function leave()
	if State.context.location == "lobby" then
		return
	end
	if os.clock() - leaveArmed < 2.5 then
		Net.send("leave")
		leaveArmed = 0
		Hud.actionbar("", nil)
	else
		leaveArmed = os.clock()
		Hud.actionbar('<font color="#FFFF55">한 번 더 누르면 로비로 돌아갑니다</font>', 2.5)
	end
end

local KEY_SLOTS = {
	[Enum.KeyCode.One] = 1,
	[Enum.KeyCode.Two] = 2,
	[Enum.KeyCode.Three] = 3,
	[Enum.KeyCode.Four] = 4,
	[Enum.KeyCode.Five] = 5,
	[Enum.KeyCode.Six] = 6,
	[Enum.KeyCode.Seven] = 7,
	[Enum.KeyCode.Eight] = 8,
	[Enum.KeyCode.Nine] = 9,
}

local function mobileButtons(gui: ScreenGui)
	local holder = Theme.create("Frame", {
		Name = "MobileButtons",
		AnchorPoint = Vector2.new(1, 1),
		Position = UDim2.new(1, -10, 1, -170),
		Size = UDim2.fromOffset(150, 240),
		BackgroundTransparency = 1,
		Parent = gui,
	})
	local function button(text: string, x: number, y: number, onDown: () -> (), onUp: (() -> ())?)
		local b = Theme.button(text, {
			Position = UDim2.fromOffset(x, y),
			Size = UDim2.fromOffset(68, 50),
			Parent = holder,
		})
		b.InputBegan:Connect(function(input)
			if input.UserInputType == Enum.UserInputType.Touch then
				onDown()
			end
		end)
		b.InputEnded:Connect(function(input)
			if input.UserInputType == Enum.UserInputType.Touch and onUp then
				onUp()
			end
		end)
	end
	button("공격", 0, 0, function()
		attackHeld = true
		tryAttack()
	end, function()
		attackHeld = false
	end)
	button("놓기", 76, 0, function()
		useHeld = true
		useStart()
	end, function()
		useHeld = false
		useEnd()
	end)
	button("가방", 0, 58, toggleInventory)
	button("시점", 76, 58, toggleCamera)
	button("웅크림", 0, 116, function()
		player:SetAttribute("SneakToggle", not player:GetAttribute("SneakToggle"))
	end)
	button("로비", 76, 116, leave)
	button("버리기", 0, 174, function()
		Net.send("drop")
	end)
end

function Controls.init()
	selection = Instance.new("SelectionBox")
	selection.Name = "BlockOutline"
	selection.Color3 = Color3.new(0, 0, 0)
	selection.LineThickness = 0.025
	selection.SurfaceColor3 = Color3.new(0, 0, 0)
	selection.SurfaceTransparency = 1
	selection.Transparency = 0.3
	selection.Parent = workspace.CurrentCamera

	Hud.onSlotClicked = selectSlot

	UserInputService.InputBegan:Connect(function(input, processed)
		if processed then
			return
		end
		local key = input.KeyCode
		local slot = KEY_SLOTS[key]
		if slot then
			selectSlot(slot)
			return
		end
		if key == Enum.KeyCode.E then
			toggleInventory()
		elseif key == Enum.KeyCode.V then
			toggleCamera()
		elseif key == Enum.KeyCode.L then
			leave()
		elseif key == Enum.KeyCode.Q and canAct() then
			Net.send("drop")
		elseif input.UserInputType == Enum.UserInputType.MouseButton1 and canAct() then
			attackHeld = true
			tryAttack()
		elseif input.UserInputType == Enum.UserInputType.MouseButton2 and canAct() then
			useHeld = true
			useStart()
		end
	end)
	UserInputService.InputEnded:Connect(function(input)
		if input.UserInputType == Enum.UserInputType.MouseButton1 then
			attackHeld = false
		elseif input.UserInputType == Enum.UserInputType.MouseButton2 then
			useHeld = false
			useEnd()
		end
	end)
	UserInputService.InputChanged:Connect(function(input, processed)
		if processed or Windows.isOpen() then
			return
		end
		if input.UserInputType == Enum.UserInputType.MouseWheel and not thirdPerson then
			local nextSlot = State.selected - math.sign(input.Position.Z)
			if nextSlot < 1 then
				nextSlot = 9
			elseif nextSlot > 9 then
				nextSlot = 1
			end
			selectSlot(nextSlot)
		end
	end)

	if UserInputService.TouchEnabled and not UserInputService.KeyboardEnabled then
		mobileButtons(Hud.gui())
	end

	Windows.onChanged = function(open)
		if open then
			attackHeld = false
			useHeld = false
			bowStart = nil
			UserInputService.MouseBehavior = Enum.MouseBehavior.Default
		end
	end

	toggleCamera() -- 1인칭 -> 3인칭 -> 1인칭 상태로 맞추기 위해 두 번
	toggleCamera()

	RunService.RenderStepped:Connect(function(dt)
		if thirdPerson and not Windows.isOpen() and UserInputService.KeyboardEnabled then
			UserInputService.MouseBehavior = Enum.MouseBehavior.LockCenter
		end
		if Windows.isOpen() then
			target = nil
		else
			updateTarget()
		end
		Hud.setCrosshairVisible(not Windows.isOpen())

		local now = os.clock()
		-- 꾹 누르고 있으면 반복 동작 (모바일 공격, 블록 연속 설치)
		if attackHeld and target and (target.kind == "player" or target.kind == "mob") and now - lastAttack > 0.18 and not UserInputService.KeyboardEnabled then
			tryAttack()
		end
		if useHeld and now - lastPlace > 0.22 then
			local def = selectedDef()
			if def and def.kind == "block" then
				tryPlace()
			end
		end
		if bowStart then
			local charge = math.clamp((now - bowStart) / 1, 0, 1)
			local bars = math.floor(charge * 10 + 0.5)
			Hud.actionbar(string.rep("■", bars) .. '<font color="#555555">' .. string.rep("■", 10 - bars) .. "</font>", nil)
		end

		-- 블록 부수기 진행
		breakCooldown -= dt
		local outlined: BasePart? = nil
		if target and target.kind == "block" and target.part then
			outlined = target.part
		end
		if attackHeld and target and target.kind == "block" and State.context.build and breakCooldown <= 0 and target.cell and target.part then
			local c = target.cell
			local key = c[1] .. "," .. c[2] .. "," .. c[3]
			if not breaking or breaking.key ~= key then
				breaking = { key = key, progress = 0 }
				Net.send("breakStart", c[1], c[2], c[3])
			end
			local stack = State.selectedStack()
			local need = Rules.breakTime(target.part.Name, if stack then stack.id else nil, breakSpeed())
			if need and breaking then
				breaking.progress += dt / need
				if breaking.progress >= 1 then
					Net.send("break", c[1], c[2], c[3])
					breaking = nil
					breakCooldown = 0.12
				end
			end
		elseif not attackHeld then
			breaking = nil
		end
		selection.Adornee = outlined
		selection.SurfaceTransparency = if breaking then 1 - math.clamp(breaking.progress, 0, 1) * 0.65 else 1
	end)
end

return Controls
