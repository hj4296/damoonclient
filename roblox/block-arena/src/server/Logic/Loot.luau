-- 스카이워즈 상자 아이템 표. weight 가 클수록 자주 나옵니다.
local Loot = {}

export type Entry = { id: string, min: number, max: number, weight: number }
export type Stack = { id: string, count: number }

local function e(id: string, weight: number, min: number?, max: number?): Entry
	return { id = id, weight = weight, min = min or 1, max = max or min or 1 }
end

Loot.Tables = {
	island = {
		rolls = { 4, 7 },
		entries = {
			e("planks", 8, 16, 32),
			e("stone", 6, 16, 24),
			e("cobble", 5, 16, 32),
			e("wood_sword", 4),
			e("stone_sword", 4),
			e("iron_sword", 1),
			e("leather_helmet", 3),
			e("leather_chestplate", 3),
			e("leather_leggings", 3),
			e("leather_boots", 3),
			e("chain_helmet", 2),
			e("chain_chestplate", 2),
			e("chain_leggings", 2),
			e("chain_boots", 2),
			e("iron_helmet", 1),
			e("iron_boots", 1),
			e("golden_apple", 2, 1, 2),
			e("snowball", 4, 4, 8),
			e("arrow", 3, 4, 10),
			e("bow", 1),
			e("pearl", 1),
			e("wood_pickaxe", 3),
			e("iron_pickaxe", 1),
			e("wood_axe", 2),
			e("tnt", 2, 1, 2),
			e("water_bucket", 2),
			e("lava_bucket", 1),
			e("fishing_rod", 2),
			e("speed_potion", 1),
			e("jump_potion", 1),
			e("heal_potion", 1),
			e("iron_sword_sharp", 1),
			e("iron_helmet_prot", 1),
			e("bow_power", 1),
		},
	},
	center = {
		rolls = { 5, 8 },
		entries = {
			e("diamond_sword", 2),
			e("iron_sword", 4),
			e("diamond_helmet", 2),
			e("diamond_chestplate", 2),
			e("diamond_leggings", 2),
			e("diamond_boots", 2),
			e("iron_helmet", 3),
			e("iron_chestplate", 3),
			e("iron_leggings", 3),
			e("iron_boots", 3),
			e("bow", 3),
			e("arrow", 4, 8, 16),
			e("golden_apple", 4, 1, 3),
			e("pearl", 3, 1, 2),
			e("tnt", 2, 2, 4),
			e("fireball", 2, 1, 2),
			e("planks", 4, 32, 48),
			e("obsidian", 1, 2, 4),
			e("diamond_pickaxe", 1),
			e("snowball", 3, 8, 16),
			e("diamond_sword_sharp", 1),
			e("diamond_chestplate_prot", 1),
			e("iron_chestplate_prot", 2),
			e("iron_leggings_prot", 2),
			e("bow_power", 2),
			e("bow_punch", 1),
			e("kb_stick", 1),
			e("lava_bucket", 2),
			e("water_bucket", 2),
			e("fishing_rod", 2),
			e("regen_potion", 2),
			e("speed_potion", 2),
			e("invis_potion", 1),
			e("hardstone", 2, 8, 16),
		},
	},
}

local function pick(entries: { Entry }, random: () -> number): Entry
	local total = 0
	for _, entry in entries do
		total += entry.weight
	end
	local r = random() * total
	for _, entry in entries do
		r -= entry.weight
		if r < 0 then
			return entry
		end
	end
	return entries[#entries]
end

local function count(entry: Entry, random: () -> number): number
	return entry.min + math.floor(random() * (entry.max - entry.min + 1))
end

function Loot.roll(tableName: string, random: () -> number): { Stack }
	local def = Loot.Tables[tableName]
	assert(def, "알 수 없는 상자 표: " .. tableName)
	local rolls = def.rolls[1] + math.floor(random() * (def.rolls[2] - def.rolls[1] + 1))
	local out = {}
	local seen = {}
	local guard = 0
	while #out < rolls and guard < 100 do
		guard += 1
		local entry = pick(def.entries, random)
		-- 같은 장비가 한 상자에 두 번 나오지 않게
		if not seen[entry.id] then
			seen[entry.id] = true
			table.insert(out, { id = entry.id, count = count(entry, random) })
		end
	end
	return out
end

-- 섬마다 첫 상자에는 싸울 수 있는 최소 장비를 넣어 줍니다.
function Loot.starter(random: () -> number): { Stack }
	local sword = if random() < 0.5 then "wood_sword" else "stone_sword"
	local pieces = { "leather_chestplate", "leather_leggings", "chain_helmet", "chain_boots" }
	local piece = pieces[1 + math.floor(random() * #pieces)] or pieces[1]
	local block = if random() < 0.5 then "planks" else "stone"
	return {
		{ id = sword, count = 1 },
		{ id = piece, count = 1 },
		{ id = block, count = 24 + math.floor(random() * 9) },
	}
end

-- 아이템을 상자 칸에 무작위로 흩어 놓습니다.
function Loot.scatter(stacks: { Stack }, size: number, random: () -> number): { [number]: Stack }
	local free = {}
	for i = 1, size do
		free[i] = i
	end
	local slots = {}
	for _, stack in stacks do
		if #free == 0 then
			break
		end
		local index = 1 + math.floor(random() * #free)
		index = math.clamp(index, 1, #free)
		local slot = table.remove(free, index) :: number
		slots[slot] = { id = stack.id, count = stack.count }
	end
	return slots
end

return Loot
