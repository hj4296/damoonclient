-- 마인크래프트식 창: 가방(E), 상자, 상점, 팀 강화, 맵 투표, 게임 선택
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local UserInputService = game:GetService("UserInputService")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Items = require(Shared:WaitForChild("Items"))
local Shop = require(Shared:WaitForChild("Shop"))
local Skins = require(Shared:WaitForChild("Skins"))
local CharacterRig = require(Shared:WaitForChild("CharacterRig"))
local Preferences = require(Shared:WaitForChild("Preferences"))
local Theme = require(script.Parent.Theme)
local IconRender = require(script.Parent.IconRender)
local State = require(script.Parent.Parent.State)
local Net = require(script.Parent.Parent.Net)
local CharacterVisuals = require(script.Parent.Parent.CharacterVisuals)

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
-- 설정 버튼을 눌렀을 때 (Main 에서 채움: 적용 + 서버에 저장)
Windows.onSetting = function(_key: string, _value: any) end
Windows.onFlyToggle = function() end

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
	local frame = window("맵 선택 (VIP)", width, 70 + count * 58 + 70)
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
	local vipButton = Theme.button("VIP 패키지 보기", {
		Position = UDim2.fromOffset(16, 70 + count * 58),
		Size = UDim2.fromOffset(width - 32, 36),
		BackgroundColor3 = Color3.fromRGB(200, 150, 30),
		Parent = frame,
	})
	vipButton.MouseButton1Click:Connect(function()
		Windows.openStore()
	end)
	table.insert(refreshers, function()
		local v = State.vote
		if not v then
			return
		end
		vipButton.Visible = not v.canVote and not v.locked
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
		if v.locked then
			footer.Text = '<font color="#AA0000">맵이 정해졌습니다</font>'
		elseif v.canVote then
			footer.Text = '<font color="#555555">VIP 가 고른 맵에서 게임을 합니다 (아무도 안 고르면 무작위)</font>'
		else
			footer.Text = '<font color="#AA6600">맵은 무작위로 정해집니다. VIP 는 원하는 맵을 고를 수 있어요.</font>'
		end
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

----------------------------------------------------------------------------
-- 상점 (게임패스)
----------------------------------------------------------------------------
local function card(parent: Instance, x: number, y: number, w: number, h: number): Frame
	local f = create("Frame", {
		Position = UDim2.fromOffset(x, y),
		Size = UDim2.fromOffset(w, h),
		BackgroundColor3 = Color3.fromRGB(170, 170, 170),
		BorderSizePixel = 0,
		Parent = parent,
	})
	create("UIStroke", { Color = Color3.fromRGB(85, 85, 85), Thickness = 2, Parent = f })
	return f
end

local function text(parent: Instance, value: string, x: number, y: number, w: number, h: number, size: number, color: Color3?)
	return create("TextLabel", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(x, y),
		Size = UDim2.fromOffset(w, h),
		Font = Theme.Font,
		TextSize = size,
		TextWrapped = true,
		RichText = true,
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = color or C.Text,
		Text = value,
		Parent = parent,
	})
end

function Windows.openStore()
	local width = 16 + #Config.PassOrder * 272
	local frame = window("상점", width, 340)
	local buttons = {}
	local prices = {}
	for i, key in Config.PassOrder do
		local pass = Config.Passes[key]
		local c = card(frame, 16 + (i - 1) * 272, 40, 256, 284)
		local slot = Theme.slot(56)
		slot.Position = UDim2.fromOffset(10, 10)
		slot.Parent = c
		local icon = IconRender.draw(pass.icon, pass.color)
		icon.Parent = slot:FindFirstChild("Icon")
		text(c, pass.name, 76, 12, 170, 24, 20, C.Text)
		prices[key] = text(c, "", 76, 38, 170, 22, 18, Color3.fromRGB(30, 120, 30))
		for j, perk in pass.perks do
			text(c, "· " .. perk, 12, 76 + (j - 1) * 40, 236, 38, 16, C.Text)
		end
		local button = Theme.button("", {
			Position = UDim2.fromOffset(12, 232),
			Size = UDim2.fromOffset(232, 40),
			BackgroundColor3 = Color3.fromRGB(60, 140, 60),
			Parent = c,
		})
		buttons[key] = button
		button.MouseButton1Click:Connect(function()
			local info = State.passes[key]
			if info and info.owned then
				if key == "skin" then
					Windows.openSkins()
				end
			else
				Net.send("buyPass", key)
			end
		end)
	end
	table.insert(refreshers, function()
		for _, key in Config.PassOrder do
			local info = State.passes[key]
			local pass = Config.Passes[key]
			local price = if info then info.price else pass.price
			prices[key].Text = string.format("R$ %d", price)
			local label = buttons[key]:FindFirstChild("Label") :: TextLabel?
			local value
			if info and info.owned then
				value = if key == "skin" then "스킨 꾸미기" else '<font color="#FFFF55">보유 중</font>'
			elseif info and not info.available then
				value = "준비 중"
			elseif info and info.studioTest then
				value = "구매 (스튜디오 시험)"
			else
				value = string.format("구매  R$ %d", price)
			end
			if label then
				label.Text = value
			end
		end
	end)
	open("store", frame)
	Net.send("passes")
	Windows.refresh()
end

----------------------------------------------------------------------------
-- 스킨 꾸미기
----------------------------------------------------------------------------
function Windows.openSkins()
	local width, height = 640, 430
	local frame = window("스킨 꾸미기", width, height)
	local draft = Skins.decode(if State.skinCode ~= "" then State.skinCode else Skins.DefaultCode)

	-- 미리보기 인형 (천천히 돌아감)
	local viewport = create("ViewportFrame", {
		Position = UDim2.fromOffset(16, 40),
		Size = UDim2.fromOffset(200, 300),
		BackgroundColor3 = Color3.fromRGB(130, 178, 230),
		Ambient = Color3.fromRGB(170, 170, 170),
		LightColor = Color3.new(1, 1, 1),
		LightDirection = Vector3.new(-1, -1, 1),
		Parent = frame,
	})
	create("UIStroke", { Color = C.SlotDark, Thickness = 2, Parent = viewport })
	local camera = Instance.new("Camera")
	camera.FieldOfView = 30
	camera.CFrame = CFrame.lookAt(Vector3.new(0, 3.2, -12), Vector3.new(0, 2.6, 0))
	camera.Parent = viewport
	viewport.CurrentCamera = camera
	local doll = CharacterRig.build(CFrame.new(0, 3, 0), false)
	doll.Parent = viewport
	local angle = 0
	local spin
	spin = RunService.RenderStepped:Connect(function(dt)
		if not frame.Parent then
			spin:Disconnect()
			return
		end
		angle += dt * 0.8
		doll:PivotTo(CFrame.new(0, 3, 0) * CFrame.Angles(0, math.sin(angle) * 0.9, 0))
	end)

	local swatches: { [string]: { [string]: GuiObject } } = {}
	local styleButtons: { [string]: { [string]: TextButton } } = {}
	local status = text(frame, "", 232, 350, 392, 22, 16, C.Text)
	local save = Theme.button("", {
		Position = UDim2.fromOffset(232, 380),
		Size = UDim2.fromOffset(250, 36),
		BackgroundColor3 = Color3.fromRGB(60, 140, 60),
		Parent = frame,
	})
	local reset = Theme.button("기본으로", {
		Position = UDim2.fromOffset(492, 380),
		Size = UDim2.fromOffset(132, 36),
		Parent = frame,
	})

	local function owned(): boolean
		local info = State.passes.skin
		return info ~= nil and info.owned
	end

	local function render()
		local code = Skins.encode(draft)
		CharacterVisuals.applySkin(doll, code)
		for key, list in swatches do
			for hex, swatch in list do
				local stroke = swatch:FindFirstChildOfClass("UIStroke")
				if stroke then
					local selected = (draft :: any)[key] == hex
					stroke.Color = if selected then Color3.new(1, 1, 1) else Color3.fromRGB(40, 40, 40)
					stroke.Thickness = if selected then 3 else 1
				end
			end
		end
		for key, list in styleButtons do
			for value, button in list do
				button.BackgroundColor3 = if (draft :: any)[key] == value then Color3.fromRGB(60, 120, 60) else C.Button
			end
		end
		local saveLabel = save:FindFirstChild("Label") :: TextLabel?
		local isDefault = code == Skins.DefaultCode
		if saveLabel then
			if owned() or isDefault then
				saveLabel.Text = if code == State.skinCode then "저장됨" else "저장하기"
			else
				local info = State.passes.skin
				saveLabel.Text = string.format("구매하고 저장 (R$ %d)", if info then info.price else Config.Passes.skin.price)
			end
		end
		status.Text = if owned()
			then '<font color="#1E7A1E">커스텀 스킨 패스 보유 중</font>'
			else '<font color="#8A5A00">입어 보기는 무료! 저장하려면 커스텀 스킨 패스가 필요해요.</font>'
	end

	-- 색 고르기 줄
	for row, key in Skins.ColorOrder do
		local y = 40 + (row - 1) * 30
		text(frame, Skins.ColorNames[key], 232, y + 3, 64, 22, 16, C.Text)
		swatches[key] = {}
		for i, hex in Skins.Palettes[key] do
			local swatch = create("TextButton", {
				AutoButtonColor = false,
				Text = "",
				BackgroundColor3 = Color3.fromHex(hex),
				BorderSizePixel = 0,
				Position = UDim2.fromOffset(300 + (i - 1) * 27, y),
				Size = UDim2.fromOffset(24, 24),
				Parent = frame,
			})
			create("UIStroke", { Color = Color3.fromRGB(40, 40, 40), Thickness = 1, ApplyStrokeMode = Enum.ApplyStrokeMode.Border, Parent = swatch })
			swatches[key][hex] = swatch
			swatch.MouseButton1Click:Connect(function()
				(draft :: any)[key] = hex
				render()
			end)
		end
	end
	-- 모양 고르기 줄
	local styleRows = {
		{ key = "hairStyle", label = "머리", values = Skins.HairStyles, names = Skins.HairStyleNames },
		{ key = "shirtStyle", label = "옷 모양", values = Skins.ShirtStyles, names = Skins.ShirtStyleNames },
	}
	for row, info in styleRows do
		local y = 222 + (row - 1) * 36
		text(frame, info.label, 232, y + 6, 64, 22, 16, C.Text)
		styleButtons[info.key] = {}
		for i, value in info.values do
			local button = Theme.button(info.names[value], {
				Position = UDim2.fromOffset(300 + (i - 1) * 82, y),
				Size = UDim2.fromOffset(78, 30),
				Parent = frame,
			})
			styleButtons[info.key][value] = button
			button.MouseLeave:Connect(function()
				render()
			end)
			button.MouseButton1Click:Connect(function()
				(draft :: any)[info.key] = value
				render()
			end)
		end
	end
	-- 프리셋
	text(frame, "프리셋", 232, 300, 64, 22, 16, C.Text)
	for i, preset in Skins.Presets do
		local button = Theme.button(preset.name, {
			Position = UDim2.fromOffset(300 + (i - 1) * 66, 296),
			Size = UDim2.fromOffset(62, 30),
			Parent = frame,
		})
		button.MouseButton1Click:Connect(function()
			draft = Skins.sanitize(preset.skin :: any)
			render()
		end)
	end

	save.MouseButton1Click:Connect(function()
		local code = Skins.encode(draft)
		if owned() or code == Skins.DefaultCode then
			Net.send("setSkin", code)
		else
			Net.send("buyPass", "skin")
		end
	end)
	reset.MouseButton1Click:Connect(function()
		draft = Skins.decode(Skins.DefaultCode)
		render()
	end)

	table.insert(refreshers, render)
	open("skins", frame)
	Net.send("passes")
	render()
end

----------------------------------------------------------------------------
-- 설정
----------------------------------------------------------------------------
function Windows.openSettings()
	local items = Preferences.Items
	local width = 460
	local frame = window("설정", width, 50 + #items * 40 + 56)
	local buttons = {}
	for i, item in items do
		local y = 40 + (i - 1) * 40
		text(frame, item.name, 18, y + 6, 220, 24, 18, C.Text)
		local button = Theme.button("", {
			Position = UDim2.fromOffset(240, y),
			Size = UDim2.fromOffset(200, 34),
			Parent = frame,
		})
		buttons[item.key] = button
		button.MouseButton1Click:Connect(function()
			if item.vip and not (State.passes.vip and State.passes.vip.owned) then
				Windows.openStore()
				return
			end
			local current = State.settings[item.key]
			local index = table.find(item.choices, current) or 1
			local nextValue = item.choices[index % #item.choices + 1]
			Windows.onSetting(item.key, nextValue)
			Windows.refresh()
		end)
	end
	local flyButton = Theme.button("지금 날기 / 내려오기", {
		Position = UDim2.fromOffset(18, 50 + #items * 40),
		Size = UDim2.fromOffset(width - 36, 36),
		BackgroundColor3 = Color3.fromRGB(200, 150, 30),
		Parent = frame,
	})
	flyButton.MouseButton1Click:Connect(function()
		Windows.onFlyToggle()
	end)
	table.insert(refreshers, function()
		local vip = State.passes.vip and State.passes.vip.owned
		for _, item in items do
			local label = buttons[item.key]:FindFirstChild("Label") :: TextLabel?
			if label then
				if item.vip and not vip then
					label.Text = '<font color="#FFD24A">VIP 전용</font>'
				else
					label.Text = tostring(item.labels[State.settings[item.key]] or State.settings[item.key])
				end
			end
		end
		flyButton.Visible = vip == true and State.context.location == "lobby" and State.settings.fly ~= false
	end)
	open("settings", frame)
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
