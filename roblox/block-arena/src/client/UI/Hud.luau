-- 게임 화면: 조준점, 핫바, 하트, 갑옷, 오른쪽 점수판, 가운데 큰 글씨, 채팅 알림, 액션바
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local TweenService = game:GetService("TweenService")
local Players = game:GetService("Players")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Items = require(Shared:WaitForChild("Items"))
local Rules = require(Shared:WaitForChild("Rules"))
local Theme = require(script.Parent.Theme)
local IconRender = require(script.Parent.IconRender)
local State = require(script.Parent.Parent.State)

local create = Theme.create
local C = Theme.Colors

local Hud = {}

local SLOT = 44
local HEART = 18

local gui: ScreenGui
local scale: UIScale
local hotbarSlots: { TextButton } = {}
local selector: Frame
local hearts: { { fill: Frame, clip: Frame } } = {}
local absorbHearts: { { fill: Frame, clip: Frame, holder: Frame } } = {}
local armorIcons: { { clip: Frame, holder: Frame } } = {}
local sidebarTitle: TextLabel
local sidebarLines: Frame
local titleGroup: CanvasGroup
local titleLabel: TextLabel
local subtitleLabel: TextLabel
local chatFrame: Frame
local actionLabel: TextLabel
local itemNameLabel: TextLabel
local hintLabel: TextLabel
local crosshair: Frame
local effectsLabel: TextLabel
local blindOverlay: Frame
local fireOverlay: Frame
local titleToken = 0
local actionToken = 0
local itemNameToken = 0

Hud.onSlotClicked = function(_index: number) end
Hud.onMenu = function(_name: string) end

local function heartWidget(parent: Instance, x: number, color: Color3, emptyColor: Color3?)
	local holder = create("Frame", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(x, 0),
		Size = UDim2.fromOffset(HEART, HEART),
		Parent = parent,
	})
	local empty = IconRender.draw("heart", emptyColor or Color3.fromRGB(45, 18, 18))
	empty.Parent = holder
	local clip = create("Frame", {
		Name = "Clip",
		BackgroundTransparency = 1,
		ClipsDescendants = true,
		Size = UDim2.fromScale(1, 1),
		Parent = holder,
	})
	local fill = IconRender.draw("heart", color)
	fill.Size = UDim2.fromOffset(HEART, HEART)
	fill.Parent = clip
	return { fill = fill, clip = clip, holder = holder }
end

local function armorWidget(parent: Instance, x: number)
	local holder = create("Frame", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(x, 0),
		Size = UDim2.fromOffset(HEART, HEART),
		Parent = parent,
	})
	local empty = IconRender.draw("chestplate", Color3.fromRGB(50, 50, 55))
	empty.Parent = holder
	local clip = create("Frame", {
		BackgroundTransparency = 1,
		ClipsDescendants = true,
		Size = UDim2.fromScale(1, 1),
		Parent = holder,
	})
	local fill = IconRender.draw("chestplate", Color3.fromRGB(220, 220, 228))
	fill.Size = UDim2.fromOffset(HEART, HEART)
	fill.Parent = clip
	return { clip = clip, holder = holder }
end

function Hud.init(playerGui: Instance)
	gui = create("ScreenGui", {
		Name = "BlockArenaHud",
		ResetOnSpawn = false,
		IgnoreGuiInset = true,
		ZIndexBehavior = Enum.ZIndexBehavior.Sibling,
		DisplayOrder = 5,
		Parent = playerGui,
	})
	scale = create("UIScale", { Parent = gui })

	-- 조준점
	crosshair = create("Frame", {
		Name = "Crosshair",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromOffset(18, 18),
		BackgroundTransparency = 1,
		Parent = gui,
	})
	for _, size in { UDim2.fromOffset(18, 2), UDim2.fromOffset(2, 18) } do
		create("Frame", {
			AnchorPoint = Vector2.new(0.5, 0.5),
			Position = UDim2.fromScale(0.5, 0.5),
			Size = size,
			BorderSizePixel = 0,
			BackgroundColor3 = C.White,
			BackgroundTransparency = 0.15,
			Parent = crosshair,
		})
	end

	-- 핫바
	local hotbar = create("Frame", {
		Name = "Hotbar",
		AnchorPoint = Vector2.new(0.5, 1),
		Position = UDim2.new(0.5, 0, 1, -8),
		Size = UDim2.fromOffset(SLOT * 9 + 4, SLOT + 4),
		BackgroundColor3 = Color3.fromRGB(20, 20, 20),
		BackgroundTransparency = 0.35,
		BorderSizePixel = 0,
		Parent = gui,
	})
	create("UIStroke", { Color = Color3.fromRGB(0, 0, 0), Thickness = 2, Parent = hotbar })
	for i = 1, 9 do
		local slot = Theme.slot(SLOT)
		slot.Name = "Slot" .. i
		slot.Position = UDim2.fromOffset(2 + (i - 1) * SLOT, 2)
		slot.BackgroundColor3 = Color3.fromRGB(90, 90, 90)
		slot.BackgroundTransparency = 0.25
		slot.Parent = hotbar
		slot.MouseButton1Click:Connect(function()
			Hud.onSlotClicked(i)
		end)
		hotbarSlots[i] = slot
	end
	selector = create("Frame", {
		Name = "Selector",
		BackgroundTransparency = 1,
		Size = UDim2.fromOffset(SLOT + 4, SLOT + 4),
		ZIndex = 6,
		Parent = hotbar,
	})
	create("UIStroke", { Color = C.White, Thickness = 3, Parent = selector })

	-- 하트와 갑옷
	local status = create("Frame", {
		Name = "Status",
		AnchorPoint = Vector2.new(0.5, 1),
		Position = UDim2.new(0.5, 0, 1, -(SLOT + 18)),
		Size = UDim2.fromOffset(SLOT * 9 + 4, HEART * 2 + 4),
		BackgroundTransparency = 1,
		Parent = gui,
	})
	local healthRow = create("Frame", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(0, HEART + 4),
		Size = UDim2.fromOffset(HEART * 10, HEART),
		Parent = status,
	})
	local absorbRow = create("Frame", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(0, 0),
		Size = UDim2.fromOffset(HEART * 10, HEART),
		Parent = status,
	})
	local armorRow = create("Frame", {
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(1, 0),
		Position = UDim2.new(1, 0, 0, HEART + 4),
		Size = UDim2.fromOffset(HEART * 10, HEART),
		Parent = status,
	})
	for i = 1, 10 do
		hearts[i] = heartWidget(healthRow, (i - 1) * (HEART - 1), Color3.fromRGB(230, 30, 30))
		local absorb = heartWidget(absorbRow, (i - 1) * (HEART - 1), Color3.fromRGB(250, 210, 40), Color3.fromRGB(80, 70, 20))
		absorb.holder.Visible = false
		absorbHearts[i] = absorb
		armorIcons[i] = armorWidget(armorRow, (10 - i) * (HEART - 1))
	end

	-- 액션바, 아이템 이름
	actionLabel = Theme.label("", {
		AnchorPoint = Vector2.new(0.5, 1),
		Position = UDim2.new(0.5, 0, 1, -(SLOT + HEART * 2 + 30)),
		Size = UDim2.fromOffset(600, 22),
		Visible = false,
		Parent = gui,
	})
	itemNameLabel = Theme.label("", {
		AnchorPoint = Vector2.new(0.5, 1),
		Position = UDim2.new(0.5, 0, 1, -(SLOT + HEART * 2 + 56)),
		Size = UDim2.fromOffset(500, 22),
		Visible = false,
		Parent = gui,
	})

	-- 오른쪽 점수판
	local sidebar = create("Frame", {
		Name = "Sidebar",
		AnchorPoint = Vector2.new(1, 0.5),
		Position = UDim2.new(1, -6, 0.5, 0),
		Size = UDim2.fromOffset(250, 40),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundColor3 = Color3.new(0, 0, 0),
		BackgroundTransparency = 0.6,
		BorderSizePixel = 0,
		Parent = gui,
	})
	create("UIListLayout", { SortOrder = Enum.SortOrder.LayoutOrder, Parent = sidebar })
	create("UIPadding", {
		PaddingLeft = UDim.new(0, 8),
		PaddingRight = UDim.new(0, 8),
		PaddingBottom = UDim.new(0, 6),
		Parent = sidebar,
	})
	sidebarTitle = create("TextLabel", {
		Name = "Title",
		BackgroundColor3 = Color3.new(0, 0, 0),
		BackgroundTransparency = 0.6,
		BorderSizePixel = 0,
		Size = UDim2.new(1, 0, 0, 28),
		Font = Theme.Font,
		TextSize = 20,
		RichText = true,
		TextColor3 = C.Yellow,
		Text = "",
		LayoutOrder = 0,
		Parent = sidebar,
	})
	sidebarLines = create("Frame", {
		Name = "Lines",
		BackgroundTransparency = 1,
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		LayoutOrder = 1,
		Parent = sidebar,
	})
	create("UIListLayout", { SortOrder = Enum.SortOrder.LayoutOrder, Parent = sidebarLines })

	-- 가운데 큰 글씨
	titleGroup = create("CanvasGroup", {
		Name = "Title",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.36),
		Size = UDim2.fromOffset(900, 130),
		BackgroundTransparency = 1,
		GroupTransparency = 1,
		Parent = gui,
	})
	titleLabel = Theme.label("", {
		Size = UDim2.new(1, 0, 0, 72),
		Parent = titleGroup,
	})
	subtitleLabel = Theme.label("", {
		Position = UDim2.fromOffset(0, 82),
		Size = UDim2.new(1, 0, 0, 32),
		Parent = titleGroup,
	})

	-- 채팅 알림 (왼쪽 아래)
	chatFrame = create("Frame", {
		Name = "Chat",
		AnchorPoint = Vector2.new(0, 1),
		Position = UDim2.new(0, 8, 1, -(SLOT + 120)),
		Size = UDim2.fromOffset(460, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		BackgroundTransparency = 1,
		Parent = gui,
	})
	create("UIListLayout", {
		SortOrder = Enum.SortOrder.LayoutOrder,
		VerticalAlignment = Enum.VerticalAlignment.Bottom,
		Parent = chatFrame,
	})

	-- 왼쪽 위 키 안내
	-- 왼쪽 위 메뉴 버튼 (로블록스 기본 버튼 줄 바로 아래)
	local menu = create("Frame", {
		Name = "Menu",
		Position = UDim2.fromOffset(12, 64),
		Size = UDim2.fromOffset(300, 34),
		BackgroundTransparency = 1,
		Parent = gui,
	})
	create("UIListLayout", {
		FillDirection = Enum.FillDirection.Horizontal,
		Padding = UDim.new(0, 8),
		SortOrder = Enum.SortOrder.LayoutOrder,
		Parent = menu,
	})
	for i, entry in { { "store", "상점", Color3.fromRGB(200, 150, 30) }, { "skins", "스킨", Color3.fromRGB(60, 130, 190) }, { "settings", "설정", C.Button } } do
		local button = Theme.button(entry[2], {
			Name = entry[1],
			Size = UDim2.fromOffset(84, 32),
			LayoutOrder = i,
			BackgroundColor3 = entry[3],
			Parent = menu,
		})
		local base = entry[3]
		button.MouseLeave:Connect(function()
			button.BackgroundColor3 = base
		end)
		button.MouseButton1Click:Connect(function()
			Hud.onMenu(entry[1])
		end)
	end

	hintLabel = create("TextLabel", {
		Name = "Hint",
		Position = UDim2.fromOffset(12, 104),
		Size = UDim2.fromOffset(520, 18),
		BackgroundTransparency = 1,
		Font = Theme.Font,
		TextSize = 14,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = C.Gray,
		TextStrokeTransparency = 0.6,
		Text = "",
		Parent = gui,
	})

	-- 받고 있는 효과 목록 (물약, 함정, 불)과 어둠/불 화면 효과
	effectsLabel = create("TextLabel", {
		Name = "Effects",
		Position = UDim2.fromOffset(12, 128),
		Size = UDim2.fromOffset(220, 120),
		BackgroundTransparency = 1,
		Font = Theme.Font,
		TextSize = 15,
		RichText = true,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextYAlignment = Enum.TextYAlignment.Top,
		TextColor3 = C.White,
		TextStrokeTransparency = 0.4,
		Text = "",
		Parent = gui,
	})
	blindOverlay = create("Frame", {
		Name = "Blind",
		Size = UDim2.fromScale(1, 1),
		BackgroundColor3 = Color3.new(0, 0, 0),
		BackgroundTransparency = 0.08,
		BorderSizePixel = 0,
		Visible = false,
		ZIndex = 0,
		Parent = gui,
	})
	fireOverlay = create("Frame", {
		Name = "Fire",
		AnchorPoint = Vector2.new(0, 1),
		Position = UDim2.fromScale(0, 1),
		Size = UDim2.fromScale(1, 0.35),
		BackgroundColor3 = Color3.fromRGB(255, 120, 20),
		BackgroundTransparency = 0.6,
		BorderSizePixel = 0,
		Visible = false,
		ZIndex = 0,
		Parent = gui,
	})
	create("UIGradient", {
		Rotation = 90,
		Transparency = NumberSequence.new({ NumberSequenceKeypoint.new(0, 1), NumberSequenceKeypoint.new(1, 0) }),
		Parent = fireOverlay,
	})
	local localPlayer = Players.LocalPlayer
	local effectTimer = 0
	RunService.Heartbeat:Connect(function(dt)
		effectTimer += dt
		if effectTimer < 0.25 then
			return
		end
		effectTimer = 0
		local now = workspace:GetServerTimeNow()
		local lines = {}
		local blind, fire = false, false
		for name, def in Config.Effects do
			local ends = localPlayer:GetAttribute("FX_" .. name)
			if type(ends) == "number" and ends > now then
				table.insert(lines, string.format('<font color="%s">%s</font> %d초', def.color, def.name, math.ceil(ends - now)))
				blind = blind or name == "blind"
				fire = fire or name == "fire"
			end
		end
		table.sort(lines)
		effectsLabel.Text = table.concat(lines, "\n")
		blindOverlay.Visible = blind
		fireOverlay.Visible = fire
	end)

	local camera = workspace.CurrentCamera
	local function rescale()
		local size = camera.ViewportSize
		scale.Scale = math.clamp(math.min(size.Y / 720, size.X / 1000), 0.55, 1.5)
	end
	camera:GetPropertyChangedSignal("ViewportSize"):Connect(rescale)
	rescale()

	State.listen("inv", Hud.refreshInventory)
	Hud.refreshInventory()
	return gui
end

function Hud.gui(): ScreenGui
	return gui
end

function Hud.refreshInventory()
	for i = 1, 9 do
		IconRender.fill(hotbarSlots[i], State.slots[i])
	end
	selector.Position = UDim2.fromOffset((State.selected - 1) * SLOT, 0)
	local points = Rules.armorPoints(State.armor)
	for i = 1, 10 do
		local value = math.clamp(points - (i - 1) * 2, 0, 2)
		armorIcons[i].clip.Size = UDim2.fromScale(value / 2, 1)
		armorIcons[i].holder.Visible = points > 0
	end
end

function Hud.showItemName()
	local stack = State.selectedStack()
	local def = if stack then Items.get(stack.id) else nil
	itemNameToken += 1
	local token = itemNameToken
	if not def then
		itemNameLabel.Visible = false
		return
	end
	itemNameLabel.Text = def.name
	itemNameLabel.Visible = true
	task.delay(1.8, function()
		if itemNameToken == token then
			itemNameLabel.Visible = false
		end
	end)
end

function Hud.setHealth(health: number, maxHealth: number, absorption: number)
	local h = math.clamp(math.ceil(health), 0, maxHealth)
	for i = 1, 10 do
		local value = math.clamp(h - (i - 1) * 2, 0, 2)
		hearts[i].clip.Size = UDim2.fromScale(value / 2, 1)
		local a = math.clamp(math.ceil(absorption) - (i - 1) * 2, 0, 2)
		absorbHearts[i].holder.Visible = a > 0
		absorbHearts[i].clip.Size = UDim2.fromScale(a / 2, 1)
	end
end

function Hud.setStatusVisible(visible: boolean)
	local status = gui:FindFirstChild("Status")
	if status and status:IsA("GuiObject") then
		status.Visible = visible
	end
end

function Hud.setSidebar(data: { title: string, lines: { string } })
	sidebarTitle.Text = data.title or ""
	local existing = sidebarLines:GetChildren()
	local labels = {}
	for _, child in existing do
		if child:IsA("TextLabel") then
			table.insert(labels, child)
		end
	end
	table.sort(labels, function(a, b)
		return a.LayoutOrder < b.LayoutOrder
	end)
	local lines = data.lines or {}
	for i, text in lines do
		local label = labels[i]
		if not label then
			label = create("TextLabel", {
				BackgroundTransparency = 1,
				Size = UDim2.new(1, 0, 0, 20),
				Font = Theme.Font,
				TextSize = 16,
				RichText = true,
				TextXAlignment = Enum.TextXAlignment.Left,
				TextColor3 = C.White,
				TextStrokeTransparency = 0.7,
				LayoutOrder = i,
				Parent = sidebarLines,
			})
		end
		label.LayoutOrder = i
		label.Text = if text == "" then " " else text
	end
	for i = #lines + 1, #labels do
		labels[i]:Destroy()
	end
end

function Hud.title(title: string, subtitle: string?, duration: number?)
	titleToken += 1
	local token = titleToken
	titleLabel.Text = title
	subtitleLabel.Text = subtitle or ""
	titleGroup.GroupTransparency = 0
	task.delay(duration or 2.5, function()
		if titleToken == token then
			TweenService:Create(titleGroup, TweenInfo.new(0.4), { GroupTransparency = 1 }):Play()
		end
	end)
end

function Hud.chat(text: string)
	local line = create("TextLabel", {
		BackgroundColor3 = Color3.new(0, 0, 0),
		BackgroundTransparency = 0.55,
		BorderSizePixel = 0,
		Size = UDim2.new(1, 0, 0, 0),
		AutomaticSize = Enum.AutomaticSize.Y,
		Font = Theme.Font,
		TextSize = 17,
		RichText = true,
		TextWrapped = true,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = C.White,
		TextStrokeTransparency = 0.75,
		Text = text,
		LayoutOrder = os.clock() * 1000 // 1,
	})
	create("UIPadding", { PaddingLeft = UDim.new(0, 4), PaddingRight = UDim.new(0, 4), Parent = line })
	line.Parent = chatFrame
	local lines = {}
	for _, child in chatFrame:GetChildren() do
		if child:IsA("TextLabel") then
			table.insert(lines, child)
		end
	end
	table.sort(lines, function(a, b)
		return a.LayoutOrder < b.LayoutOrder
	end)
	while #lines > 9 do
		table.remove(lines, 1):Destroy()
	end
	task.delay(10, function()
		if line.Parent then
			local tween = TweenService:Create(line, TweenInfo.new(1), { TextTransparency = 1, BackgroundTransparency = 1, TextStrokeTransparency = 1 })
			tween:Play()
			tween.Completed:Wait()
			line:Destroy()
		end
	end)
end

function Hud.actionbar(text: string, duration: number?)
	actionToken += 1
	local token = actionToken
	actionLabel.Text = text
	actionLabel.Visible = text ~= ""
	if duration then
		task.delay(duration, function()
			if actionToken == token then
				actionLabel.Visible = false
			end
		end)
	end
end

function Hud.setChatVisible(visible: boolean)
	chatFrame.Visible = visible
end

function Hud.setHint(text: string)
	hintLabel.Text = text
end

function Hud.setCrosshairVisible(visible: boolean)
	crosshair.Visible = visible
end

return Hud
