-- 아이템 정의. 블록은 Blocks 에서 자동으로 아이템이 만들어집니다.
local Blocks = require(script.Parent.Blocks)

local Items = {}

export type ItemDef = {
	id: string,
	name: string,
	kind: string, -- block, sword, pickaxe, axe, shears, armor, bow, arrow, food, throwable, currency, menu
	stack: number,
	icon: string,
	color: Color3,
	damage: number?,
	speed: number?, -- 도구 채굴 속도 배율
	tier: number?,
	armorSlot: string?,
	armorPoints: number?,
	armorTier: string?,
	block: string?,
	projectile: string?,
	action: string?,
	lore: { string }?,
}

local defs: { [string]: ItemDef } = {}
Items.defs = defs

local function rgb(r: number, g: number, b: number): Color3
	return Color3.fromRGB(r, g, b)
end

local TIER = {
	wood = rgb(160, 118, 64),
	stone = rgb(140, 140, 140),
	iron = rgb(226, 226, 230),
	gold = rgb(250, 210, 70),
	diamond = rgb(90, 232, 222),
	leather = rgb(156, 96, 56),
	chain = rgb(160, 162, 172),
}
Items.TierColors = TIER

local function add(id: string, data: { [string]: any })
	data.id = id
	data.stack = data.stack or 1
	data.color = data.color or rgb(200, 200, 200)
	defs[id] = data :: any
end

-- 무기
add("wood_sword", { name = "나무 검", kind = "sword", damage = 4, tier = 1, icon = "sword", color = TIER.wood })
add("stone_sword", { name = "돌 검", kind = "sword", damage = 5, tier = 2, icon = "sword", color = TIER.stone })
add("iron_sword", { name = "철 검", kind = "sword", damage = 6, tier = 3, icon = "sword", color = TIER.iron })
add("diamond_sword", {
	name = "다이아몬드 검",
	kind = "sword",
	damage = 7,
	tier = 4,
	icon = "sword",
	color = TIER.diamond,
})

-- 도구
add("wood_pickaxe", { name = "나무 곡괭이", kind = "pickaxe", speed = 2, damage = 2, tier = 1, icon = "pickaxe", color = TIER.wood })
add("iron_pickaxe", { name = "철 곡괭이", kind = "pickaxe", speed = 4, damage = 3, tier = 2, icon = "pickaxe", color = TIER.iron })
add("diamond_pickaxe", {
	name = "다이아몬드 곡괭이",
	kind = "pickaxe",
	speed = 7,
	damage = 4,
	tier = 3,
	icon = "pickaxe",
	color = TIER.diamond,
})
add("wood_axe", { name = "나무 도끼", kind = "axe", speed = 2, damage = 3, tier = 1, icon = "axe", color = TIER.wood })
add("iron_axe", { name = "철 도끼", kind = "axe", speed = 4, damage = 4, tier = 2, icon = "axe", color = TIER.iron })
add("diamond_axe", { name = "다이아몬드 도끼", kind = "axe", speed = 7, damage = 5, tier = 3, icon = "axe", color = TIER.diamond })
add("shears", { name = "가위", kind = "shears", speed = 5, damage = 1, icon = "shears", color = TIER.iron })

-- 갑옷 (마인크래프트처럼 부위마다 방어 점수가 있고, 점수 1당 피해 4% 감소)
local ARMOR_POINTS = {
	leather = { helmet = 1, chestplate = 3, leggings = 2, boots = 1 },
	chain = { helmet = 2, chestplate = 5, leggings = 4, boots = 1 },
	iron = { helmet = 2, chestplate = 6, leggings = 5, boots = 2 },
	diamond = { helmet = 3, chestplate = 8, leggings = 6, boots = 3 },
}
local ARMOR_NAMES = { leather = "가죽", chain = "사슬", iron = "철", diamond = "다이아몬드" }
local PIECE_NAMES = { helmet = "투구", chestplate = "흉갑", leggings = "레깅스", boots = "부츠" }
Items.ArmorSlots = { "helmet", "chestplate", "leggings", "boots" }
Items.ArmorTiers = { "leather", "chain", "iron", "diamond" }
for tier, points in ARMOR_POINTS do
	for piece, value in points do
		add(tier .. "_" .. piece, {
			name = ARMOR_NAMES[tier] .. " " .. PIECE_NAMES[piece],
			kind = "armor",
			armorSlot = piece,
			armorPoints = value,
			armorTier = tier,
			icon = piece,
			color = TIER[tier],
		})
	end
end

-- 원거리/소모품
add("bow", { name = "활", kind = "bow", icon = "bow", color = TIER.wood, lore = { "꾹 눌렀다 떼면 발사" } })
add("arrow", { name = "화살", kind = "arrow", stack = 64, icon = "arrow", color = TIER.stone })
add("golden_apple", {
	name = "황금 사과",
	kind = "food",
	stack = 64,
	icon = "apple",
	color = TIER.gold,
	lore = { "체력 회복 + 보호막 2칸" },
})
add("pearl", {
	name = "순간이동 진주",
	kind = "throwable",
	projectile = "pearl",
	stack = 16,
	icon = "pearl",
	color = rgb(40, 150, 130),
	lore = { "떨어진 곳으로 순간이동" },
})
add("fireball", {
	name = "화염구",
	kind = "throwable",
	projectile = "fireball",
	stack = 64,
	icon = "fireball",
	color = rgb(255, 120, 30),
	lore = { "부딪히면 폭발" },
})
add("snowball", {
	name = "눈덩이",
	kind = "throwable",
	projectile = "snowball",
	stack = 16,
	icon = "snowball",
	color = rgb(245, 250, 255),
	lore = { "맞은 사람을 밀어냄" },
})

-- 자원
add("iron", { name = "철 주괴", kind = "currency", stack = 64, icon = "ingot", color = TIER.iron })
add("gold", { name = "금 주괴", kind = "currency", stack = 64, icon = "ingot", color = TIER.gold })
add("diamond", { name = "다이아몬드", kind = "currency", stack = 64, icon = "gem", color = TIER.diamond })
add("emerald", { name = "에메랄드", kind = "currency", stack = 64, icon = "gem", color = rgb(40, 210, 90) })

-- 메뉴 아이템(로비/대기실)
add("compass", {
	name = "게임 선택",
	kind = "menu",
	action = "games",
	icon = "compass",
	color = rgb(200, 200, 210),
	lore = { "우클릭으로 미니게임 고르기" },
})
add("vote_paper", {
	name = "맵 선택 (VIP)",
	kind = "menu",
	action = "vote",
	icon = "paper",
	color = rgb(240, 240, 230),
	lore = { "우클릭으로 맵 고르기", "VIP 가 없으면 맵은 무작위" },
})
add("leave_bed", {
	name = "로비로 돌아가기",
	kind = "menu",
	action = "leave",
	icon = "bed",
	color = rgb(220, 50, 50),
	lore = { "우클릭으로 나가기" },
})

-- 블록 아이템 자동 생성
for id, block in Blocks.defs do
	if not block.noItem then
		add(id, {
			name = block.name,
			kind = "block",
			stack = 64,
			icon = if block.tnt then "tnt" else "block",
			color = block.color,
			block = id,
		})
	end
end

function Items.get(id: string): ItemDef?
	return defs[id]
end

function Items.maxStack(id: string): number
	local def = defs[id]
	return if def then def.stack else 64
end

function Items.isBlock(id: string): boolean
	local def = defs[id]
	return def ~= nil and def.kind == "block"
end

return Items
