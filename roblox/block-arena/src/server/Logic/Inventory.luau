-- 플레이어 가방 (마인크래프트처럼 핫바 9칸 + 가방 27칸, 갑옷 4칸, 마우스로 집은 아이템)
-- Roblox API 를 쓰지 않는 순수 로직이라 테스트로 바로 확인할 수 있습니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local Items = require(ReplicatedStorage.Shared.Items)

local Inventory = {}

Inventory.Size = 36
Inventory.HotbarSize = 9

export type Stack = { id: string, count: number }
export type Container = { [number]: Stack? }
export type Inv = {
	slots: Container,
	armor: { [string]: string? },
	selected: number,
	cursor: Stack?,
	dirty: boolean,
	armorLocked: boolean,
}

function Inventory.new(): Inv
	return {
		slots = {},
		armor = {},
		selected = 1,
		cursor = nil,
		dirty = true,
		armorLocked = false,
	}
end

function Inventory.clear(inv: Inv)
	table.clear(inv.slots)
	table.clear(inv.armor)
	inv.cursor = nil
	inv.selected = 1
	inv.armorLocked = false
	inv.dirty = true
end

-- 같은 아이템 칸에 먼저 합치고, 남으면 빈 칸(핫바부터)에 넣습니다. 못 넣은 개수를 돌려줍니다.
function Inventory.addTo(container: Container, size: number, id: string, count: number): number
	local max = Items.maxStack(id)
	for i = 1, size do
		local stack = container[i]
		if count <= 0 then
			break
		end
		if stack and stack.id == id and stack.count < max then
			local moved = math.min(max - stack.count, count)
			stack.count += moved
			count -= moved
		end
	end
	for i = 1, size do
		if count <= 0 then
			break
		end
		if container[i] == nil then
			local moved = math.min(max, count)
			container[i] = { id = id, count = moved }
			count -= moved
		end
	end
	return count
end

function Inventory.add(inv: Inv, id: string, count: number): number
	local left = Inventory.addTo(inv.slots, Inventory.Size, id, count)
	if left ~= count then
		inv.dirty = true
	end
	return left
end

function Inventory.canFit(inv: Inv, id: string, count: number): boolean
	local max = Items.maxStack(id)
	local space = 0
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		if stack == nil then
			space += max
		elseif stack.id == id then
			space += max - stack.count
		end
		if space >= count then
			return true
		end
	end
	return space >= count
end

function Inventory.count(inv: Inv, id: string): number
	local total = 0
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		if stack and stack.id == id then
			total += stack.count
		end
	end
	return total
end

function Inventory.remove(inv: Inv, id: string, count: number): boolean
	if Inventory.count(inv, id) < count then
		return false
	end
	for i = Inventory.Size, 1, -1 do
		local stack = inv.slots[i]
		if count <= 0 then
			break
		end
		if stack and stack.id == id then
			local taken = math.min(stack.count, count)
			stack.count -= taken
			count -= taken
			if stack.count <= 0 then
				inv.slots[i] = nil
			end
		end
	end
	inv.dirty = true
	return true
end

function Inventory.removeAll(inv: Inv, id: string): number
	local total = 0
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		if stack and stack.id == id then
			total += stack.count
			inv.slots[i] = nil
		end
	end
	if total > 0 then
		inv.dirty = true
	end
	return total
end

function Inventory.selectedStack(inv: Inv): (Stack?, number)
	return inv.slots[inv.selected], inv.selected
end

function Inventory.consumeSlot(inv: Inv, index: number, count: number): boolean
	local stack = inv.slots[index]
	if not stack or stack.count < count then
		return false
	end
	stack.count -= count
	if stack.count <= 0 then
		inv.slots[index] = nil
	end
	inv.dirty = true
	return true
end

function Inventory.findKind(inv: Inv, kind: string): (number?, Stack?)
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		local def = if stack then Items.get(stack.id) else nil
		if stack and def and def.kind == kind then
			return i, stack
		end
	end
	return nil, nil
end

-- 같은 종류(예: 검)를 모두 지우고 새 아이템 하나를 그 자리에 넣습니다.
function Inventory.replaceKind(inv: Inv, kind: string, newId: string)
	local first = nil
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		local def = if stack then Items.get(stack.id) else nil
		if def and def.kind == kind then
			first = first or i
			inv.slots[i] = nil
		end
	end
	if first then
		inv.slots[first] = { id = newId, count = 1 }
	else
		Inventory.add(inv, newId, 1)
	end
	inv.dirty = true
end

-- 마인크래프트식 클릭: 왼쪽 = 통째로 집기/놓기/바꾸기, 오른쪽 = 반 집기/하나 놓기
function Inventory.click(inv: Inv, container: Container, index: number, button: string)
	local slot = container[index]
	local cursor = inv.cursor
	if button == "right" then
		if cursor == nil then
			if slot then
				local half = math.ceil(slot.count / 2)
				inv.cursor = { id = slot.id, count = half }
				slot.count -= half
				if slot.count <= 0 then
					container[index] = nil
				end
			end
		elseif slot == nil then
			container[index] = { id = cursor.id, count = 1 }
			cursor.count -= 1
		elseif slot.id == cursor.id and slot.count < Items.maxStack(slot.id) then
			slot.count += 1
			cursor.count -= 1
		else
			container[index], inv.cursor = cursor, slot
		end
	else
		if cursor == nil then
			container[index] = nil
			inv.cursor = slot
		elseif slot == nil then
			container[index] = cursor
			inv.cursor = nil
		elseif slot.id == cursor.id then
			local max = Items.maxStack(slot.id)
			local moved = math.min(max - slot.count, cursor.count)
			slot.count += moved
			cursor.count -= moved
		else
			container[index], inv.cursor = cursor, slot
		end
	end
	if inv.cursor and inv.cursor.count <= 0 then
		inv.cursor = nil
	end
	inv.dirty = true
end

-- 갑옷 칸 클릭: 맞는 부위 갑옷만 낄 수 있습니다.
function Inventory.clickArmor(inv: Inv, slotName: string): boolean
	if inv.armorLocked then
		return false
	end
	local cursor = inv.cursor
	local current = inv.armor[slotName]
	if cursor then
		local def = Items.get(cursor.id)
		if not def or def.kind ~= "armor" or def.armorSlot ~= slotName or cursor.count ~= 1 then
			return false
		end
		inv.armor[slotName] = cursor.id
		inv.cursor = if current then { id = current, count = 1 } else nil
	elseif current then
		inv.armor[slotName] = nil
		inv.cursor = { id = current, count = 1 }
	else
		return false
	end
	inv.dirty = true
	return true
end

-- 손에 든 갑옷을 우클릭으로 바로 착용
function Inventory.equipFromSlot(inv: Inv, index: number): boolean
	if inv.armorLocked then
		return false
	end
	local stack = inv.slots[index]
	local def = if stack then Items.get(stack.id) else nil
	if not stack or not def or def.kind ~= "armor" or not def.armorSlot then
		return false
	end
	local current = inv.armor[def.armorSlot]
	inv.armor[def.armorSlot] = stack.id
	inv.slots[index] = if current then { id = current, count = 1 } else nil
	inv.dirty = true
	return true
end

-- 쉬프트 클릭: 다른 칸 묶음으로 통째로 옮깁니다.
function Inventory.quickMove(from: Container, index: number, to: Container, toSize: number): boolean
	local stack = from[index]
	if not stack then
		return false
	end
	local left = Inventory.addTo(to, toSize, stack.id, stack.count)
	if left == stack.count then
		return false
	end
	if left <= 0 then
		from[index] = nil
	else
		stack.count = left
	end
	return true
end

-- 가방 안에서 쉬프트 클릭하면 핫바 <-> 가방 사이로 옮깁니다.
function Inventory.quickMoveInside(inv: Inv, index: number): boolean
	local stack = inv.slots[index]
	if not stack then
		return false
	end
	local fromHotbar = index <= Inventory.HotbarSize
	local first = if fromHotbar then Inventory.HotbarSize + 1 else 1
	local last = if fromHotbar then Inventory.Size else Inventory.HotbarSize
	local max = Items.maxStack(stack.id)
	for pass = 1, 2 do
		for i = first, last do
			if stack.count <= 0 then
				break
			end
			local target = inv.slots[i]
			if pass == 1 and target and target.id == stack.id and target.count < max then
				local moved = math.min(max - target.count, stack.count)
				target.count += moved
				stack.count -= moved
			elseif pass == 2 and target == nil then
				inv.slots[i] = { id = stack.id, count = stack.count }
				stack.count = 0
			end
		end
	end
	if stack.count <= 0 then
		inv.slots[index] = nil
	end
	inv.dirty = true
	return true
end

-- 창을 닫을 때 마우스에 들고 있던 아이템을 가방으로 돌려놓습니다.
function Inventory.returnCursor(inv: Inv)
	local cursor = inv.cursor
	if cursor then
		inv.cursor = nil
		Inventory.add(inv, cursor.id, cursor.count)
		inv.dirty = true
	end
end

function Inventory.serialize(inv: Inv)
	local slots = {}
	for i = 1, Inventory.Size do
		local stack = inv.slots[i]
		if stack then
			slots[tostring(i)] = { stack.id, stack.count }
		end
	end
	return {
		slots = slots,
		armor = table.clone(inv.armor),
		selected = inv.selected,
		cursor = if inv.cursor then { inv.cursor.id, inv.cursor.count } else nil,
		armorLocked = inv.armorLocked,
	}
end

function Inventory.serializeContainer(container: Container, size: number)
	local out = {}
	for i = 1, size do
		local stack = container[i]
		if stack then
			out[tostring(i)] = { stack.id, stack.count }
		end
	end
	return out
end

return Inventory
