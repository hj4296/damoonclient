-- 바닥에 떨어진 아이템 꾸러미. 닿으면 주워집니다(스카이워즈에서 죽은 사람 아이템).
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Items = require(ReplicatedStorage.Shared.Items)
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)
local Inventory = require(script.Parent.Logic.Inventory)

local Drops = {}

function Drops.spawn(
	parent: Instance,
	position: Vector3,
	stacks: { Inventory.Stack },
	canPickup: (Player) -> boolean,
	ignore: Player?,
	ignoreFor: number?
)
	local ignoreUntil = os.clock() + (ignoreFor or 0)
	if #stacks == 0 then
		return
	end
	local part = Instance.new("Part")
	part.Name = "ItemDrop"
	part.Anchored = true
	part.CanCollide = false
	part.CanQuery = false
	part.Size = Vector3.new(1.4, 1.4, 1.4)
	local first = Items.get(stacks[1].id)
	part.Color = if first then first.color else Color3.new(1, 1, 1)
	part.Material = Enum.Material.SmoothPlastic
	part.CFrame = CFrame.new(position) * CFrame.Angles(math.rad(30), 0, math.rad(30))
	part:AddTag("Spin")

	local gui = Instance.new("BillboardGui")
	gui.Size = UDim2.new(5, 0, 1, 0)
	gui.StudsOffset = Vector3.new(0, 1.6, 0)
	gui.MaxDistance = 40
	gui.LightInfluence = 0
	local label = Instance.new("TextLabel")
	label.BackgroundTransparency = 1
	label.Size = UDim2.new(1, 0, 1, 0)
	label.Font = Enum.Font.Arcade
	label.TextScaled = true
	label.TextColor3 = Color3.fromRGB(255, 255, 85)
	label.TextStrokeTransparency = 0.3
	label.Parent = gui
	gui.Parent = part

	local remaining = stacks
	local function updateLabel()
		local total = 0
		for _, stack in remaining do
			total += stack.count
		end
		label.Text = string.format("아이템 %d개", total)
	end
	updateLabel()

	local busy = false
	part.Touched:Connect(function(hit)
		if busy then
			return
		end
		local model = hit:FindFirstAncestorOfClass("Model")
		local player = model and Players:GetPlayerFromCharacter(model)
		local state = player and Session.get(player)
		if not player or not state or not state.alive or not canPickup(player) then
			return
		end
		if player == ignore and os.clock() < ignoreUntil then
			return
		end
		busy = true
		local left = {}
		local picked = false
		for _, stack in remaining do
			local def = Items.get(stack.id)
			if def and def.kind == "armor" and def.armorSlot and state.inv.armor[def.armorSlot] == nil and not state.inv.armorLocked then
				state.inv.armor[def.armorSlot] = stack.id
				state.inv.dirty = true
				picked = true
			else
				local rest = Inventory.add(state.inv, stack.id, stack.count)
				if rest < stack.count then
					picked = true
				end
				if rest > 0 then
					table.insert(left, { id = stack.id, count = rest })
				end
			end
		end
		remaining = left
		if picked then
			Net.send(player, "sound", "pickup")
		end
		if #remaining == 0 then
			part:Destroy()
		else
			updateLabel()
		end
		busy = false
	end)
	part.Parent = parent
end

-- 플레이어 가방의 모든 아이템(갑옷 포함)을 꺼내 목록으로 만듭니다.
function Drops.takeAll(inv: Inventory.Inv): { Inventory.Stack }
	local out = {}
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		if stack then
			table.insert(out, { id = stack.id, count = stack.count })
		end
	end
	for _, slot in Items.ArmorSlots do
		local id = inv.armor[slot]
		if id then
			table.insert(out, { id = id, count = 1 })
		end
	end
	if inv.cursor then
		table.insert(out, { id = inv.cursor.id, count = inv.cursor.count })
	end
	Inventory.clear(inv)
	return out
end

return Drops
