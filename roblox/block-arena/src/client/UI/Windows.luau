-- 마인크래프트식 창: 가방(E), 상자, 상점, 팀 강화, 맵 투표, 게임 선택
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local UserInputService = game:GetService("UserInputService")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Items = require(Shared:WaitForChild("Items"))
local Shop = require(Shared:WaitForChild("Shop"))
local Theme = require(script.Parent.Theme)
local IconRender = require(script.Parent.IconRender)
local State = require(script.Parent.Parent.State)
local Net = require(script.Parent.Parent.Net)

local create = Theme.create
local C = Theme.Colors

local Windows = {}

local SLOT = 40
local GAP = 2

local gui: ScreenGui
local layer: Frame
local current: Frame? = nil
local currentKind: string? = nil
local refreshers: { () -> () } = {}
local tooltip: Frame
local tooltipLabel: TextLabel
local cursorIcon: Frame
local shopCategory = 1

Windows.onChanged = function(_open: boolean) end

local function hex(color: Color3): string
	return "#" .. color:ToHex()
end

-- 마우스를 올리면 보이는 설명창
local function setTooltip(lines: { string }?)
	if not lines or #lines == 0 then
		tooltip.Visible = false
		return
	end
	tooltipLabel.Text = table.concat(lines, "\n")
	tooltip.Visible = true
end

local function attachTooltip(button: GuiObject, getLines: () -> { string }?)
	button.MouseEnter:Connect(function()
		setTooltip(getLines())
	end)
	button.MouseLeave:Connect(function()
		setTooltip(nil)
	end)
end

local function itemLines(id: string?): { string }?
	if not id then
		return nil
	end
	local def = Items.get(id)
	if not def then
		return nil
	end
	local lines = { def.name }
	if def.damage and (def.kind == "sword" or def.kind == "axe" or def.kind == "pickaxe") then
		table.insert(lines, string.format('<font color="#5555FF">공격력 +%d</font>', def.damage))
	end
	if def.armorPoints then
		table.insert(lines, string.format('<font color="#5555FF">방어 +%d</font>', def.armorPoints))
	end
	for _, line in def.lore or {} do
		table.insert(lines, '<font color="#AAAAAA">' .. line .. "</font>")
	end
	return lines
end

local function clickButton(input: InputObject): string
	if input.UserInputType == Enum.UserInputType.MouseButton2 then
		return "right"
	end
	if UserInputService:IsKeyDown(Enum.KeyCode.LeftShift) or UserInputService:IsKeyDown(Enum.KeyCode.RightShift) then
		return "shift"
	end
	return "left"
end

local function bindSlotClick(slot: GuiButton, onClick: (string) -> ())
	slot.InputBegan:Connect(function(input)
		if input.UserInputType == Enum.UserInputType.MouseButton1 or input.UserInputType == Enum.UserInputType.MouseButton2 then
			onClick(clickButton(input))
		elseif input.UserInputType == Enum.UserInputType.Touch then
			onClick("left")
		end
	end)
end

local function heading(parent: Instance, text: string, y: number, width: number)
	return create("TextLabel", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(12, y),
		Size = UDim2.fromOffset(width - 24, 20),
		Font = Theme.Font,
		TextSize = 18,
		RichText = true,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = C.Text,
		Text = text,
		Parent = parent,
	})
end

local function window(title: string, width: number, height: number): Frame
	local frame = Theme.panel({
		Name = "Window",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromOffset(width, height),
		Parent = layer,
	})
	heading(frame, title, 8, width)
	-- 일인칭에서도 마우스를 쓸 수 있게 해 주는 모달 버튼
	create("TextButton", {
		Name = "Modal",
		Modal = true,
		BackgroundTransparency = 1,
		Text = "",
		Size = UDim2.fromOffset(1, 1),
		Parent = frame,
	})
	local close = Theme.button("X", {
		AnchorPoint = Vector2.new(1, 0),
		Position = UDim2.new(1, -8, 0, 6),
		Size = UDim2.fromOffset(26, 24),
		Parent = frame,
	})
	close.MouseButton1Click:Connect(function()
		Windows.close()
	end)
	return frame
end

-- 칸 묶음 (가로 columns 개)
local function grid(parent: Instance, x: number, y: number, count: number, columns: number, onSlot: (number, TextButton) -> ())
	for i = 1, count do
		local col = (i - 1) % columns
		local row = (i - 1) // columns
		local slot = Theme.slot(SLOT)
		slot.Position = UDim2.fromOffset(x + col * (SLOT + GAP), y + row * (SLOT + GAP))
		slot.Parent = parent
		onSlot(i, slot)
	end
end

local function playerInventory(frame: Frame, x: number, y: number)
	local slots: { [number]: TextButton } = {}
	heading(frame, "가방", y - 22, 300)
	-- 가방 27칸 (10~36), 그 아래 핫바 9칸 (1~9)
	grid(frame, x, y, 27, 9, function(i, slot)
		local index = i + 9
		slots[index] = slot
		bindSlotClick(slot, function(button)
			Net.send("invClick", "inv", index, button)
		end)
		attachTooltip(slot, function()
			local stack = State.slots[index]
			return itemLines(if stack then stack.id else nil)
		end)
	end)
	grid(frame, x, y + 3 * (SLOT + GAP) + 8, 9, 9, function(i, slot)
		slots[i] = slot
		bindSlotClick(slot, function(button)
			Net.send("invClick", "inv", i, button)
		end)
		attachTooltip(slot, function()
			local stack = State.slots[i]
			return itemLines(if stack then stack.id else nil)
		end)
	end)
	table.insert(refreshers, function()
		for index, slot in slots do
			IconRender.fill(slot, State.slots[index])
		end
	end)
end

local GRID_WIDTH = 9 * (SLOT + GAP) - GAP

function Windows.close()
	if current then
		current:Destroy()
	end
	if currentKind == "inventory" or currentKind == "chest" then
		Net.send("closeContainer")
	end
	current = nil
	currentKind = nil
	table.clear(refreshers)
	setTooltip(nil)
	Windows.onChanged(false)
end

function Windows.isOpen(): boolean
	return current ~= nil
end

function Windows.kind(): string?
	return currentKind
end

local function open(kind: string, frame: Frame)
	if current then
		current:Destroy()
		table.clear(refreshers)
	end
	current = frame
	currentKind = kind
	Windows.onChanged(true)
end

function Windows.refresh()
	for _, fn in refreshers do
		fn()
	end
end

function Windows.openInventory()
	local width = GRID_WIDTH + 24
	local frame = window("가방", width, 420)
	-- 갑옷 칸
	local armorSlots = {}
	for i, slotName in Items.ArmorSlots do
		local slot = Theme.slot(SLOT)
		slot.Position = UDim2.fromOffset(12, 34 + (i - 1) * (SLOT + GAP))
		slot.Parent = frame
		armorSlots[slotName] = slot
		bindSlotClick(slot, function()
			Net.send("invClick", "armor", slotName)
		end)
		attachTooltip(slot, function()
			local id = State.armor[slotName]
			local lines = itemLines(id)
			if not lines then
				local names = { helmet = "투구", chestplate = "흉갑", leggings = "레깅스", boots = "부츠" }
				return { '<font color="#AAAAAA">' .. names[slotName] .. " 칸</font>" }
			end
			return lines
		end)
	end
	create("TextLabel", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(12 + SLOT + 12, 40),
		Size = UDim2.fromOffset(GRID_WIDTH - SLOT - 12, 120),
		Font = Theme.Font,
		TextSize = 15,
		TextWrapped = true,
		RichText = true,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextYAlignment = Enum.TextYAlignment.Top,
		TextColor3 = C.Text,
		Text = "왼쪽 클릭: 집기/놓기\n오른쪽 클릭: 반만 집기, 하나 놓기\nShift + 클릭: 빠르게 옮기기\n갑옷을 왼쪽 칸에 넣으면 착용",
		Parent = frame,
	})
	table.insert(refreshers, function()
		for slotName, slot in armorSlots do
			local id = State.armor[slotName]
			IconRender.fill(slot, if id then { id = id, count = 1 } else nil)
		end
	end)
	playerInventory(frame, 12, 34 + 4 * (SLOT + GAP) + 28)
	open("inventory", frame)
	Windows.refresh()
end

function Windows.openChest(id: number, title: string, contents)
	State.chest = { id = id, title = title, slots = State.decode(contents) }
	local width = GRID_WIDTH + 24
	local frame = window(title, width, 390)
	local chestSlots = {}
	grid(frame, 12, 34, 27, 9, function(i, slot)
		chestSlots[i] = slot
		bindSlotClick(slot, function(button)
			Net.send("invClick", "chest", i, button)
		end)
		attachTooltip(slot, function()
			local stack = State.chest and State.chest.slots[i]
			return itemLines(if stack then stack.id else nil)
		end)
	end)
	table.insert(refreshers, function()
		local chest = State.chest
		for i, slot in chestSlots do
			IconRender.fill(slot, chest and chest.slots[i])
		end
	end)
	playerInventory(frame, 12, 34 + 3 * (SLOT + GAP) + 30)
	open("chest", frame)
	Windows.refresh()
end

function Windows.updateChest(id: number, contents)
	if State.chest and State.chest.id == id then
		State.chest.slots = State.decode(contents)
		Windows.refresh()
	end
end

local function costText(cost: Shop.Cost?): string
	if not cost then
		return ""
	end
	local def = Items.get(cost.item)
	local color = if def then hex(def.color) else "#FFFFFF"
	local have = 0
	for _, stack in State.slots do
		if stack and stack.id == cost.item then
			have += stack.count
		end
	end
	local name = if def then def.name else cost.item
	local mark = if have >= cost.amount then '<font color="#55FF55">살 수 있음</font>' else '<font color="#FF5555">부족함</font>'
	return string.format('가격: <font color="%s">%s %d개</font> (%s)', color, name, cost.amount, mark)
end

local function shopEntry(item: Shop.ShopItem, shop): (string, number, Shop.Cost?, { string })
	local team = shop.team or "red"
	local lore = {}
	if item.tiers and item.toolKind then
		local level = shop[item.toolKind] or 0
		local nextTier = item.tiers[level + 1]
		if not nextTier then
			local top = item.tiers[#item.tiers]
			return top.give, 1, nil, { '<font color="#55FF55">최고 단계입니다</font>' }
		end
		for _, line in item.lore or {} do
			table.insert(lore, line)
		end
		return nextTier.give, 1, nextTier.cost, lore
	end
	if item.armor then
		local order = { leather = 1, chain = 2, iron = 3, diamond = 4 }
		if order[item.armor] <= order[shop.armor or "leather"] then
			table.insert(lore, '<font color="#55FF55">이미 가지고 있음</font>')
		end
	end
	if item.permanent and shop.shears then
		table.insert(lore, '<font color="#55FF55">이미 가지고 있음</font>')
	end
	for _, line in item.lore or {} do
		table.insert(lore, line)
	end
	local id = item.icon or Shop.resolve(item.give or "", team)
	return Shop.resolve(id, team), item.count or 1, item.cost, lore
end

function Windows.openShop(kind: string, shop)
	State.shop = shop
	local width = GRID_WIDTH + 24
	if kind == "upgrades" then
		local frame = window("팀 강화 (다이아몬드)", width, 200)
		local slots = {}
		for i, upgrade in Shop.Upgrades do
			local slot = Theme.slot(SLOT + 8)
			slot.Position = UDim2.fromOffset(12 + (i - 1) * (SLOT + 20), 44)
			slot.Parent = frame
			slots[i] = slot
			slot.MouseButton1Click:Connect(function()
				Net.send("upgrade", upgrade.key)
			end)
			attachTooltip(slot, function()
				local s = State.shop or {}
				local level = (s.upgrades or {})[upgrade.key] or 0
				local lines = { string.format("%s (%d/%d단계)", upgrade.name, level, #upgrade.costs) }
				for _, line in upgrade.lore do
					table.insert(lines, '<font color="#AAAAAA">' .. line .. "</font>")
				end
				local cost = upgrade.costs[level + 1]
				if cost then
					table.insert(lines, costText({ item = "diamond", amount = cost }))
					table.insert(lines, '<font color="#FFFF55">클릭해서 구매</font>')
				else
					table.insert(lines, '<font color="#55FF55">최고 단계입니다</font>')
				end
				return lines
			end)
		end
		local levelLabels = {}
		for i in Shop.Upgrades do
			levelLabels[i] = create("TextLabel", {
				BackgroundTransparency = 1,
				Position = UDim2.fromOffset(12 + (i - 1) * (SLOT + 20), 44 + SLOT + 12),
				Size = UDim2.fromOffset(SLOT + 8, 18),
				Font = Theme.Font,
				TextSize = 14,
				TextColor3 = C.Text,
				Text = "",
				Parent = frame,
			})
		end
		table.insert(refreshers, function()
			local s = State.shop or {}
			for i, upgrade in Shop.Upgrades do
				IconRender.fill(slots[i], { id = upgrade.icon, count = 1 })
				local level = (s.upgrades or {})[upgrade.key] or 0
				levelLabels[i].Text = string.format("%d/%d", level, #upgrade.costs)
			end
		end)
		heading(frame, '<font color="#555555">마우스를 올리면 설명이 보여요. 다이아몬드는 가운데 쪽 섬에서 모아요.</font>', 140, width)
		open("upgrades", frame)
		Windows.refresh()
		return
	end

	local frame = window("아이템 상점", width, 250)
	local tabs = {}
	for i, category in Shop.Categories do
		local tab = Theme.slot(SLOT)
		tab.Position = UDim2.fromOffset(12 + (i - 1) * (SLOT + GAP), 34)
		tab.Parent = frame
		tabs[i] = tab
		tab.MouseButton1Click:Connect(function()
			shopCategory = i
			Windows.refresh()
		end)
		attachTooltip(tab, function()
			return { category.name }
		end)
	end
	local itemSlots = {}
	for i = 1, 9 do
		local slot = Theme.slot(SLOT)
		slot.Position = UDim2.fromOffset(12 + (i - 1) * (SLOT + GAP), 34 + SLOT + 28)
		slot.Parent = frame
		itemSlots[i] = slot
		slot.MouseButton1Click:Connect(function()
			local category = Shop.Categories[shopCategory]
			local item = category and category.items[i]
			if item then
				Net.send("buy", item.key)
			end
		end)
		attachTooltip(slot, function()
			local category = Shop.Categories[shopCategory]
			local item = category and category.items[i]
			if not item then
				return nil
			end
			local id, count, cost, lore = shopEntry(item :: any, State.shop or {})
			local def = Items.get(id)
			local name = item.name or (if def then def.name else id)
			local lines = { if count > 1 then string.format("%s x%d", name, count) else name }
			for _, line in lore do
				table.insert(lines, '<font color="#AAAAAA">' .. line .. "</font>")
			end
			if cost then
				table.insert(lines, costText(cost))
				table.insert(lines, '<font color="#FFFF55">클릭해서 구매</font>')
			end
			return lines
		end)
	end
	local categoryLabel = heading(frame, "", 34 + SLOT + 6, width)
	heading(frame, '<font color="#555555">철·금은 우리 섬, 에메랄드는 가운데 섬 생성기에서 모아요.</font>', 34 + 2 * SLOT + 40, width)
	table.insert(refreshers, function()
		local s = State.shop or {}
		for i, category in Shop.Categories do
			IconRender.fill(tabs[i], { id = Shop.resolve(category.icon, s.team), count = 1 })
			tabs[i].BackgroundColor3 = if i == shopCategory then C.SlotHover else C.Slot
		end
		local category = Shop.Categories[shopCategory]
		categoryLabel.Text = category.name
		for i, slot in itemSlots do
			local item = category.items[i]
			if item then
				local id, count = shopEntry(item :: any, s)
				IconRender.fill(slot, { id = id, count = count })
				slot.Visible = true
			else
				slot.Visible = false
			end
		end
	end)
	open("shop", frame)
	Windows.refresh()
end

function Windows.updateShop(shop)
	State.shop = shop
	if currentKind == "shop" or currentKind == "upgrades" then
		Windows.refresh()
	end
end

function Windows.openVote()
	local vote = State.vote
	if not vote then
		return
	end
	local count = #vote.maps
	local width = 420
	local frame = window("맵 투표", width, 70 + count * 58 + 30)
	local buttons = {}
	for i, map in vote.maps do
		local button = Theme.button("", {
			Position = UDim2.fromOffset(16, 40 + (i - 1) * 58),
			Size = UDim2.fromOffset(width - 32, 50),
			Parent = frame,
		})
		buttons[i] = button
		button.MouseButton1Click:Connect(function()
			Net.send("vote", map.id)
		end)
	end
	local footer = heading(frame, "", 44 + count * 58, width)
	table.insert(refreshers, function()
		local v = State.vote
		if not v then
			return
		end
		for i, map in v.maps do
			local button = buttons[i]
			if button then
				local label = button:FindFirstChild("Label") :: TextLabel?
				local mark = if v.mine == map.id then '<font color="#55FF55">▶ </font>' else ""
				local chosen = if v.chosen == map.id then ' <font color="#FFFF55">[선택됨]</font>' else ""
				if label then
					label.Text = string.format("%s%s  <font color=\"#FFFF55\">%d표</font>%s", mark, map.name, map.votes, chosen)
				end
			end
		end
		footer.Text = if v.locked then '<font color="#AA0000">투표가 끝났습니다</font>' else '<font color="#555555">가장 많은 표를 받은 맵에서 게임을 합니다</font>'
	end)
	open("vote", frame)
	Windows.refresh()
end

function Windows.openGames()
	local width = 3 * 140 + 40
	local frame = window("미니게임 선택", width, 250)
	local labels = {}
	for i, modeId in Config.ModeOrder do
		local mode = Config.Modes[modeId]
		local x = 16 + (i - 1) * 140
		local slot = Theme.slot(64)
		slot.Position = UDim2.fromOffset(x + 34, 40)
		slot.Parent = frame
		IconRender.fill(slot, { id = if modeId == "bedwars" then "leave_bed" elseif modeId == "skywars" then "iron_sword" else "tnt", count = 1 })
		slot.MouseButton1Click:Connect(function()
			Net.send("join", modeId)
			Windows.close()
		end)
		attachTooltip(slot, function()
			return { mode.name, '<font color="#AAAAAA">' .. mode.description .. "</font>", '<font color="#FFFF55">클릭해서 참가</font>' }
		end)
		create("TextLabel", {
			BackgroundTransparency = 1,
			Position = UDim2.fromOffset(x, 110),
			Size = UDim2.fromOffset(132, 22),
			Font = Theme.Font,
			TextSize = 18,
			TextColor3 = C.Text,
			Text = mode.name,
			Parent = frame,
		})
		labels[modeId] = create("TextLabel", {
			BackgroundTransparency = 1,
			Position = UDim2.fromOffset(x, 134),
			Size = UDim2.fromOffset(132, 40),
			Font = Theme.Font,
			TextSize = 14,
			TextWrapped = true,
			TextColor3 = C.Text,
			Text = "",
			Parent = frame,
		})
		local join = Theme.button("참가", {
			Position = UDim2.fromOffset(x + 16, 182),
			Size = UDim2.fromOffset(100, 34),
			Parent = frame,
		})
		join.MouseButton1Click:Connect(function()
			Net.send("join", modeId)
			Windows.close()
		end)
	end
	table.insert(refreshers, function()
		for modeId, label in labels do
			local info = State.lobbyInfo[modeId]
			label.Text = if info then string.format("대기 %d명\n게임 중 %d명", info.waiting, info.playing) else ""
		end
	end)
	open("games", frame)
	Windows.refresh()
end

function Windows.init(parent: ScreenGui)
	gui = parent
	layer = create("Frame", {
		Name = "Windows",
		BackgroundTransparency = 1,
		Size = UDim2.fromScale(1, 1),
		ZIndex = 10,
		Parent = gui,
	})
	tooltip = create("Frame", {
		Name = "Tooltip",
		BackgroundColor3 = C.Tooltip,
		BackgroundTransparency = 0.05,
		BorderSizePixel = 0,
		AutomaticSize = Enum.AutomaticSize.XY,
		Size = UDim2.fromOffset(0, 0),
		Visible = false,
		ZIndex = 40,
		Parent = gui,
	})
	create("UIStroke", { Color = C.TooltipBorder, Thickness = 2, Parent = tooltip })
	create("UIPadding", {
		PaddingLeft = UDim.new(0, 6),
		PaddingRight = UDim.new(0, 6),
		PaddingTop = UDim.new(0, 4),
		PaddingBottom = UDim.new(0, 4),
		Parent = tooltip,
	})
	tooltipLabel = create("TextLabel", {
		BackgroundTransparency = 1,
		AutomaticSize = Enum.AutomaticSize.XY,
		Size = UDim2.fromOffset(0, 0),
		Font = Theme.Font,
		TextSize = 16,
		RichText = true,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = C.White,
		Text = "",
		ZIndex = 41,
		Parent = tooltip,
	})
	cursorIcon = create("Frame", {
		Name = "Cursor",
		BackgroundTransparency = 1,
		Size = UDim2.fromOffset(SLOT, SLOT),
		AnchorPoint = Vector2.new(0.5, 0.5),
		Visible = false,
		ZIndex = 45,
		Parent = gui,
	})
	create("Frame", { Name = "Icon", BackgroundTransparency = 1, Size = UDim2.fromScale(1, 1), Parent = cursorIcon })
	Theme.label("", {
		Name = "Count",
		AnchorPoint = Vector2.new(1, 1),
		Position = UDim2.new(1, -2, 1, -1),
		Size = UDim2.new(1, -2, 0.42, 0),
		TextXAlignment = Enum.TextXAlignment.Right,
		Parent = cursorIcon,
	})

	RunService.RenderStepped:Connect(function()
		local mouse = UserInputService:GetMouseLocation()
		local uiScale = gui:FindFirstChildOfClass("UIScale")
		local s = if uiScale then uiScale.Scale else 1
		local pos = UDim2.fromOffset(mouse.X / s, mouse.Y / s)
		tooltip.Position = pos + UDim2.fromOffset(16, 8)
		cursorIcon.Position = pos
		cursorIcon.Visible = current ~= nil and State.cursor ~= nil
	end)
	State.listen("inv", function()
		IconRender.fill(cursorIcon, State.cursor)
		Windows.refresh()
	end)
end

return Windows
