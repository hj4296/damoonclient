-- 블록으로 만든 NPC (상점 주인, 로비 미니게임 안내원)
local Npc = {}

export type Options = {
	name: string,
	subtitle: string?,
	shirt: Color3,
	pants: Color3?,
	skin: Color3?,
	actionText: string,
	objectText: string?,
}

local function block(model: Model, name: string, size: Vector3, cframe: CFrame, color: Color3): Part
	local part = Instance.new("Part")
	part.Name = name
	part.Anchored = true
	part.Size = size
	part.CFrame = cframe
	part.Color = color
	part.Material = Enum.Material.SmoothPlastic
	part.TopSurface = Enum.SurfaceType.Smooth
	part.BottomSurface = Enum.SurfaceType.Smooth
	part.Parent = model
	return part
end

-- feet: 발바닥 위치, look: 바라볼 곳
function Npc.create(parent: Instance, feet: Vector3, look: Vector3, opts: Options): (Model, ProximityPrompt)
	local model = Instance.new("Model")
	model.Name = "NPC_" .. opts.name
	local flat = Vector3.new(look.X, feet.Y, look.Z)
	local base = if (flat - feet).Magnitude > 0.1 then CFrame.lookAt(feet, flat) else CFrame.new(feet)
	local skin = opts.skin or Color3.fromRGB(240, 198, 160)
	local pants = opts.pants or Color3.fromRGB(60, 50, 40)

	block(model, "LeftLeg", Vector3.new(0.95, 2.2, 1), base * CFrame.new(-0.48, 1.1, 0), pants)
	block(model, "RightLeg", Vector3.new(0.95, 2.2, 1), base * CFrame.new(0.48, 1.1, 0), pants)
	local torso = block(model, "Torso", Vector3.new(1.95, 2.2, 1), base * CFrame.new(0, 3.3, 0), opts.shirt)
	block(model, "LeftArm", Vector3.new(0.95, 2.2, 1), base * CFrame.new(-1.45, 3.3, 0), opts.shirt)
	block(model, "RightArm", Vector3.new(0.95, 2.2, 1), base * CFrame.new(1.45, 3.3, 0), opts.shirt)
	local head = block(model, "Head", Vector3.new(1.9, 1.9, 1.9), base * CFrame.new(0, 5.35, 0), skin)
	-- 눈
	for _, side in { -0.45, 0.45 } do
		local eye = block(model, "Eye", Vector3.new(0.35, 0.3, 0.05), head.CFrame * CFrame.new(side, 0.15, -0.96), Color3.fromRGB(30, 30, 40))
		eye.CanCollide = false
	end
	model.PrimaryPart = torso

	local gui = Instance.new("BillboardGui")
	gui.Name = "Label"
	gui.Size = UDim2.new(8, 0, 2.2, 0)
	gui.StudsOffset = Vector3.new(0, 3.4, 0)
	gui.MaxDistance = 80
	gui.LightInfluence = 0
	gui.Parent = head
	local title = Instance.new("TextLabel")
	title.Name = "Title"
	title.BackgroundTransparency = 1
	title.Size = UDim2.new(1, 0, 0.55, 0)
	title.Font = Enum.Font.Arcade
	title.TextScaled = true
	title.TextColor3 = Color3.fromRGB(255, 255, 85)
	title.TextStrokeTransparency = 0.3
	title.Text = opts.name
	title.Parent = gui
	local sub = Instance.new("TextLabel")
	sub.Name = "Subtitle"
	sub.BackgroundTransparency = 1
	sub.Position = UDim2.new(0, 0, 0.55, 0)
	sub.Size = UDim2.new(1, 0, 0.45, 0)
	sub.Font = Enum.Font.Arcade
	sub.TextScaled = true
	sub.TextColor3 = Color3.fromRGB(220, 220, 220)
	sub.TextStrokeTransparency = 0.4
	sub.Text = opts.subtitle or ""
	sub.Parent = gui

	local prompt = Instance.new("ProximityPrompt")
	prompt.ActionText = opts.actionText
	prompt.ObjectText = opts.objectText or opts.name
	prompt.KeyboardKeyCode = Enum.KeyCode.F
	prompt.HoldDuration = 0
	prompt.MaxActivationDistance = 11
	prompt.RequiresLineOfSight = false
	prompt.Parent = torso

	model.Parent = parent
	return model, prompt
end

function Npc.setSubtitle(model: Model, text: string)
	local head = model:FindFirstChild("Head")
	local gui = head and head:FindFirstChild("Label")
	local sub = gui and gui:FindFirstChild("Subtitle")
	if sub and sub:IsA("TextLabel") then
		sub.Text = text
	end
end

return Npc
