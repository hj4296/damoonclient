-- 픽셀 아이콘 그리기: 같은 색이 이어지는 칸은 한 프레임으로 합쳐서 프레임 수를 줄입니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Icons = require(Shared:WaitForChild("Icons"))
local Items = require(Shared:WaitForChild("Items"))

local IconRender = {}

local cache: { [string]: Frame } = {}

local function build(name: string, color: Color3): Frame
	local art = Icons.Art[name] or Icons.Art.question
	local size = Icons.Size
	local holder = Instance.new("Frame")
	holder.Name = "Pixels"
	holder.BackgroundTransparency = 1
	holder.Size = UDim2.fromScale(1, 1)
	for row, line in art do
		local col = 1
		while col <= #line do
			local char = string.sub(line, col, col)
			local run = 1
			while col + run <= #line and string.sub(line, col + run, col + run) == char do
				run += 1
			end
			local pixelColor = Icons.colorFor(char, color)
			if pixelColor then
				local pixel = Instance.new("Frame")
				pixel.BorderSizePixel = 0
				pixel.BackgroundColor3 = pixelColor
				pixel.Position = UDim2.fromScale((col - 1) / size, (row - 1) / size)
				pixel.Size = UDim2.fromScale(run / size, 1 / size)
				pixel.Parent = holder
			end
			col += run
		end
	end
	return holder
end

function IconRender.draw(name: string, color: Color3): Frame
	local key = name .. "|" .. color:ToHex()
	local template = cache[key]
	if not template then
		template = build(name, color)
		cache[key] = template
	end
	return template:Clone()
end

function IconRender.item(id: string): Frame
	local def = Items.get(id)
	if not def then
		return IconRender.draw("question", Color3.new(1, 1, 1))
	end
	return IconRender.draw(def.icon, def.color)
end

-- 칸(Theme.slot)에 아이템을 표시합니다. 같은 아이템이면 다시 그리지 않습니다.
function IconRender.fill(slot: GuiObject, stack: { id: string, count: number }?)
	local iconHolder = slot:FindFirstChild("Icon")
	local count = slot:FindFirstChild("Count")
	if not iconHolder then
		return
	end
	local id = if stack then stack.id else ""
	if slot:GetAttribute("ItemId") ~= id then
		slot:SetAttribute("ItemId", id)
		iconHolder:ClearAllChildren()
		if stack then
			local pixels = IconRender.item(stack.id)
			pixels.ZIndex = 2
			for _, child in pixels:GetChildren() do
				if child:IsA("GuiObject") then
					child.ZIndex = (slot.ZIndex or 1) + 1
				end
			end
			pixels.Parent = iconHolder
		end
	end
	if count and count:IsA("TextLabel") then
		count.Text = if stack and stack.count > 1 then tostring(stack.count) else ""
	end
end

return IconRender
