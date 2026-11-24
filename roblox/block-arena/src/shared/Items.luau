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
	knockback: number?,
	power: number?,
	punch: number?,
	effect: string?,
	duration: number?,
	liquid: string?,
	deploy: string?,
	enchanted: boolean?,
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

-- 마법이 붙은 무기와 갑옷 (스카이워즈 상자)
add("iron_sword_sharp", { name = "철 검 (날카로움 I)", kind = "sword", damage = 7, tier = 3, icon = "sword", color = TIER.iron:Lerp(rgb(190, 120, 255), 0.25), enchanted = true, lore = { "날카로움 I" } })
add("diamond_sword_sharp", { name = "다이아몬드 검 (날카로움 I)", kind = "sword", damage = 8, tier = 4, icon = "sword", color = TIER.diamond:Lerp(rgb(190, 120, 255), 0.25), enchanted = true, lore = { "날카로움 I" } })
for _, tier in { "iron", "diamond" } do
	for piece, value in ARMOR_POINTS[tier] do
		add(tier .. "_" .. piece .. "_prot", {
			name = ARMOR_NAMES[tier] .. " " .. PIECE_NAMES[piece] .. " (보호 I)",
			kind = "armor",
			armorSlot = piece,
			armorPoints = value + 1,
			armorTier = tier,
			enchanted = true,
			icon = piece,
			color = TIER[tier]:Lerp(rgb(190, 120, 255), 0.25),
			lore = { "보호 I" },
		})
	end
end

-- 특수 무기
add("kb_stick", { name = "넉백 막대기", kind = "stick", damage = 1, knockback = 1.8, icon = "stick", color = rgb(150, 110, 60), enchanted = true, lore = { "밀치기: 맞은 사람을 멀리 날림" } })
add("bow_power", { name = "활 (힘 I)", kind = "bow", power = 1.5, icon = "bow", color = TIER.wood:Lerp(rgb(190, 120, 255), 0.3), enchanted = true, lore = { "화살 피해 1.5배" } })
add("bow_punch", {
	name = "활 (힘 I, 밀치기 I)",
	kind = "bow",
	power = 1.5,
	punch = 2.2,
	icon = "bow",
	color = TIER.wood:Lerp(rgb(190, 120, 255), 0.5),
	enchanted = true,
	lore = { "화살 피해 1.5배", "맞은 사람을 멀리 밀어냄" },
})
add("fishing_rod", { name = "낚싯대", kind = "rod", icon = "rod", color = TIER.wood, lore = { "맞힌 사람을 끌어당김" } })

-- 물약 (마시면 효과)
add("speed_potion", { name = "신속 물약", kind = "potion", effect = "speed", duration = 45, stack = 16, icon = "potion", color = rgb(110, 190, 255), lore = { "45초 동안 빨라짐" } })
add("jump_potion", { name = "도약 물약", kind = "potion", effect = "jump", duration = 45, stack = 16, icon = "potion", color = rgb(110, 240, 110), lore = { "45초 동안 높이 뜀" } })
add("invis_potion", { name = "투명화 물약", kind = "potion", effect = "invis", duration = 30, stack = 16, icon = "potion", color = rgb(190, 190, 215), lore = { "30초 동안 투명 (공격하면 풀림)" } })
add("heal_potion", { name = "치유 물약", kind = "potion", effect = "heal", duration = 0, stack = 16, icon = "potion", color = rgb(250, 70, 90), lore = { "체력 4칸 회복" } })
add("regen_potion", { name = "재생 물약", kind = "potion", effect = "regen", duration = 20, stack = 16, icon = "potion", color = rgb(240, 120, 210), lore = { "20초 동안 체력 회복" } })
add("magic_milk", { name = "마법 우유", kind = "potion", effect = "milk", duration = 30, stack = 16, icon = "potion", color = rgb(245, 245, 240), lore = { "30초 동안 함정에 안 걸림" } })

-- 양동이 (블록 놓듯이 우클릭)
add("water_bucket", { name = "물 양동이", kind = "bucket", liquid = "water_source", icon = "bucket", color = rgb(60, 110, 220), lore = { "물: 떨어져도 안 다치고 TNT 를 막음" } })
add("lava_bucket", { name = "용암 양동이", kind = "bucket", liquid = "lava_flow", icon = "bucket", color = rgb(255, 120, 24), lore = { "닿으면 불에 탐" } })

-- 설치/소환 (블록 놓듯이 우클릭)
add("bridge_egg", { name = "다리 알", kind = "throwable", projectile = "bridge_egg", stack = 16, icon = "egg", color = rgb(236, 226, 196), lore = { "날아간 길에 양털 다리가 생김" } })
add("popup_tower", { name = "팝업 타워", kind = "deploy", deploy = "tower", stack = 16, icon = "tower", color = rgb(200, 60, 50), lore = { "그 자리에 사다리 달린 탑이 세워짐" } })
add("golem_egg", { name = "드림 디펜더", kind = "deploy", deploy = "golem", stack = 16, icon = "golem", color = rgb(214, 214, 204), lore = { "우리 팀을 지키는 철 골렘 (4분)" } })
add("bed_bug", { name = "베드 버그", kind = "throwable", projectile = "bed_bug", stack = 16, icon = "bug", color = rgb(150, 150, 156), lore = { "떨어진 곳에 좀벌레가 나타남 (15초)" } })

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
add("kit_selector", {
	name = "키트 고르기",
	kind = "menu",
	action = "kits",
	icon = "kit",
	color = rgb(160, 112, 54),
	lore = { "우클릭으로 키트와 능력 고르기" },
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
