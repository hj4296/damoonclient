-- 마인크래프트 느낌의 화면 부품: 회색 입체 창, 움푹 들어간 칸, 그림자 글씨, 버튼
local Theme = {}

Theme.Font = Enum.Font.Arcade

Theme.Colors = {
	Panel = Color3.fromRGB(198, 198, 198),
	PanelLight = Color3.fromRGB(255, 255, 255),
	PanelDark = Color3.fromRGB(85, 85, 85),
	Outline = Color3.fromRGB(0, 0, 0),
	Slot = Color3.fromRGB(139, 139, 139),
	SlotDark = Color3.fromRGB(55, 55, 55),
	SlotLight = Color3.fromRGB(255, 255, 255),
	SlotHover = Color3.fromRGB(197, 197, 197),
	Text = Color3.fromRGB(64, 64, 64),
	White = Color3.fromRGB(255, 255, 255),
	Shadow = Color3.fromRGB(62, 62, 62),
	Yellow = Color3.fromRGB(255, 255, 85),
	Green = Color3.fromRGB(85, 255, 85),
	Red = Color3.fromRGB(255, 85, 85),
	Gray = Color3.fromRGB(170, 170, 170),
	Button = Color3.fromRGB(111, 111, 111),
	ButtonHover = Color3.fromRGB(126, 136, 191),
	Tooltip = Color3.fromRGB(16, 0, 16),
	TooltipBorder = Color3.fromRGB(40, 0, 100),
}

local C = Theme.Colors

function Theme.create(className: string, props: { [string]: any }?, children: { Instance }?): any
	local instance = Instance.new(className)
	local parent = nil
	for key, value in props or {} do
		if key == "Parent" then
			parent = value
		else
			(instance :: any)[key] = value
		end
	end
	for _, child in children or {} do
		child.Parent = instance
	end
	if parent then
		instance.Parent = parent
	end
	return instance
end

local create = Theme.create

local function strip(parent: Instance, color: Color3, position: UDim2, size: UDim2, z: number?)
	return create("Frame", {
		Name = "Bevel",
		BackgroundColor3 = color,
		BorderSizePixel = 0,
		Position = position,
		Size = size,
		ZIndex = z or 1,
		Parent = parent,
	})
end

-- 바깥 검은 테두리 + 밝은 위/왼쪽, 어두운 아래/오른쪽 테두리가 있는 회색 창
function Theme.panel(props: { [string]: any }?): Frame
	local frame = create("Frame", {
		BackgroundColor3 = C.Panel,
		BorderSizePixel = 0,
	})
	for key, value in props or {} do
		if key ~= "Parent" then
			(frame :: any)[key] = value
		end
	end
	create("UIStroke", { Color = C.Outline, Thickness = 2, Parent = frame })
	strip(frame, C.PanelLight, UDim2.new(0, 0, 0, 0), UDim2.new(1, -3, 0, 3))
	strip(frame, C.PanelLight, UDim2.new(0, 0, 0, 0), UDim2.new(0, 3, 1, -3))
	strip(frame, C.PanelDark, UDim2.new(0, 3, 1, -3), UDim2.new(1, -3, 0, 3))
	strip(frame, C.PanelDark, UDim2.new(1, -3, 0, 3), UDim2.new(0, 3, 1, -3))
	if props and props.Parent then
		frame.Parent = props.Parent
	end
	return frame
end

-- 아이템 칸: 위/왼쪽이 어둡고 아래/오른쪽이 밝아서 움푹 들어가 보임
function Theme.slot(size: number): TextButton
	local button = create("TextButton", {
		Name = "Slot",
		AutoButtonColor = false,
		BackgroundColor3 = C.Slot,
		BorderSizePixel = 0,
		Size = UDim2.fromOffset(size, size),
		Text = "",
	})
	strip(button, C.SlotDark, UDim2.new(0, 0, 0, 0), UDim2.new(1, -2, 0, 2))
	strip(button, C.SlotDark, UDim2.new(0, 0, 0, 0), UDim2.new(0, 2, 1, -2))
	strip(button, C.SlotLight, UDim2.new(0, 2, 1, -2), UDim2.new(1, -2, 0, 2))
	strip(button, C.SlotLight, UDim2.new(1, -2, 0, 2), UDim2.new(0, 2, 1, -2))
	create("Frame", {
		Name = "Icon",
		BackgroundTransparency = 1,
		Position = UDim2.new(0.12, 0, 0.12, 0),
		Size = UDim2.new(0.76, 0, 0.76, 0),
		ZIndex = 2,
		Parent = button,
	})
	Theme.label("", {
		Name = "Count",
		AnchorPoint = Vector2.new(1, 1),
		Position = UDim2.new(1, -2, 1, -1),
		Size = UDim2.new(1, -2, 0.42, 0),
		TextXAlignment = Enum.TextXAlignment.Right,
		TextColor3 = C.White,
		ZIndex = 4,
		Parent = button,
	})
	button.MouseEnter:Connect(function()
		button.BackgroundColor3 = C.SlotHover
	end)
	button.MouseLeave:Connect(function()
		button.BackgroundColor3 = C.Slot
	end)
	return button
end

local function plain(text: string): string
	return (string.gsub(text, "<[^>]+>", ""))
end

-- 그림자가 있는 글씨 (마인크래프트 글씨처럼 오른쪽 아래로 어두운 그림자).
-- 바깥 TextLabel 의 글자는 투명하고, 안쪽의 Shadow/Front 두 글씨가 실제로 보입니다.
-- 바깥의 Text, TextColor3, 정렬을 바꾸면 안쪽도 같이 바뀝니다.
function Theme.label(text: string, props: { [string]: any }?): TextLabel
	local label = create("TextLabel", {
		BackgroundTransparency = 1,
		Font = Theme.Font,
		Text = text,
		TextScaled = true,
		RichText = true,
		TextColor3 = C.White,
	})
	for key, value in props or {} do
		if key ~= "Parent" then
			(label :: any)[key] = value
		end
	end
	local front: TextLabel, shadow: TextLabel
	local function make(name: string, offset: number, z: number): TextLabel
		return create("TextLabel", {
			Name = name,
			BackgroundTransparency = 1,
			Position = UDim2.new(0, offset, 0, offset),
			Size = UDim2.new(1, 0, 1, 0),
			ZIndex = z,
			Parent = label,
		})
	end
	shadow = make("Shadow", 2, 1)
	front = make("Front", 0, 2)
	local function sync()
		for _, child in { shadow, front } do
			child.Font = label.Font
			child.TextScaled = label.TextScaled
			child.TextSize = label.TextSize
			child.TextXAlignment = label.TextXAlignment
			child.TextYAlignment = label.TextYAlignment
			child.TextWrapped = label.TextWrapped
		end
		front.RichText = label.RichText
		front.Text = label.Text
		front.TextColor3 = label.TextColor3
		shadow.RichText = false
		shadow.Text = plain(label.Text)
		shadow.TextColor3 = C.Shadow
	end
	sync()
	label.TextTransparency = 1
	for _, property in { "Text", "TextColor3", "TextXAlignment", "TextYAlignment", "Font", "TextSize", "TextScaled", "TextWrapped" } do
		label:GetPropertyChangedSignal(property):Connect(sync)
	end
	if props and props.Parent then
		label.Parent = props.Parent
	end
	return label
end

-- 회색 마인크래프트 버튼
function Theme.button(text: string, props: { [string]: any }?): TextButton
	local button = create("TextButton", {
		AutoButtonColor = false,
		BackgroundColor3 = C.Button,
		BorderSizePixel = 0,
		Text = "",
	})
	for key, value in props or {} do
		if key ~= "Parent" then
			(button :: any)[key] = value
		end
	end
	create("UIStroke", { Color = C.Outline, Thickness = 2, Parent = button })
	strip(button, Color3.fromRGB(170, 170, 170), UDim2.new(0, 0, 0, 0), UDim2.new(1, 0, 0, 2))
	strip(button, Color3.fromRGB(60, 60, 60), UDim2.new(0, 0, 1, -3), UDim2.new(1, 0, 0, 3))
	Theme.label(text, {
		Name = "Label",
		Position = UDim2.new(0, 6, 0.18, 0),
		Size = UDim2.new(1, -12, 0.62, 0),
		ZIndex = 3,
		Parent = button,
	})
	button.MouseEnter:Connect(function()
		button.BackgroundColor3 = C.ButtonHover
	end)
	button.MouseLeave:Connect(function()
		button.BackgroundColor3 = C.Button
	end)
	if props and props.Parent then
		button.Parent = props.Parent
	end
	return button
end

return Theme
