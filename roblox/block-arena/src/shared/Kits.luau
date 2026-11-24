-- 스카이워즈 키트(시작 장비)와 능력(경기 내내 붙는 효과). 대기실에서 고르고, 유리관이 열릴 때 받습니다.
local Kits = {}

export type Kit = {
	id: string,
	name: string,
	icon: string, -- 아이콘으로 보여 줄 아이템 id
	items: { { id: string, count: number } },
	lore: { string },
}

export type Perk = {
	id: string,
	name: string,
	icon: string,
	lore: { string },
}

local function stack(id: string, count: number?)
	return { id = id, count = count or 1 }
end

Kits.List = {
	{
		id = "explorer",
		name = "탐험가",
		icon = "wood_pickaxe",
		items = { stack("wood_pickaxe"), stack("wood_axe"), stack("planks", 16) },
		lore = { "나무 곡괭이, 나무 도끼", "판자 16개" },
	},
	{
		id = "knight",
		name = "기사",
		icon = "stone_sword",
		items = { stack("stone_sword"), stack("chain_chestplate") },
		lore = { "돌 검, 사슬 흉갑" },
	},
	{
		id = "archer",
		name = "궁수",
		icon = "bow",
		items = { stack("bow"), stack("arrow", 10), stack("leather_helmet") },
		lore = { "활, 화살 10개", "가죽 투구" },
	},
	{
		id = "builder",
		name = "건축가",
		icon = "planks",
		items = { stack("planks", 48), stack("stone", 24), stack("wood_axe") },
		lore = { "판자 48개, 돌 24개", "나무 도끼" },
	},
	{
		id = "miner",
		name = "광부",
		icon = "iron_pickaxe",
		items = { stack("iron_pickaxe"), stack("cobble", 32) },
		lore = { "철 곡괭이, 조약돌 32개" },
	},
	{
		id = "healer",
		name = "치유사",
		icon = "regen_potion",
		items = { stack("regen_potion"), stack("heal_potion", 2), stack("golden_apple") },
		lore = { "재생 물약, 치유 물약 2개", "황금 사과" },
	},
	{
		id = "fisher",
		name = "낚시꾼",
		icon = "fishing_rod",
		items = { stack("fishing_rod"), stack("snowball", 8) },
		lore = { "낚싯대, 눈덩이 8개" },
	},
	{
		id = "demolisher",
		name = "폭파병",
		icon = "tnt",
		items = { stack("tnt", 3), stack("fireball", 1) },
		lore = { "TNT 3개, 화염구 1개" },
	},
	{
		id = "lavaman",
		name = "용암술사",
		icon = "lava_bucket",
		items = { stack("lava_bucket"), stack("water_bucket") },
		lore = { "용암 양동이, 물 양동이" },
	},
	{
		id = "jumper",
		name = "점프왕",
		icon = "jump_potion",
		items = { stack("jump_potion", 2), stack("pearl") },
		lore = { "도약 물약 2개", "순간이동 진주" },
	},
} :: { Kit }

Kits.Perks = {
	{ id = "lifesteal", name = "처치 회복", icon = "golden_apple", lore = { "상대를 처치하면", "체력 4칸 회복" } },
	{ id = "arrow_return", name = "화살 회수", icon = "arrow", lore = { "화살을 맞히면 30% 확률로", "화살을 돌려받음" } },
	{ id = "feather", name = "깃털 낙하", icon = "snowball", lore = { "떨어질 때 받는 피해 절반" } },
	{ id = "sturdy", name = "단단한 몸", icon = "iron_chestplate", lore = { "밀려나는 거리 35% 감소" } },
	{ id = "lucky_miner", name = "행운의 곡괭이", icon = "iron_pickaxe", lore = { "블록을 부수면 25% 확률로", "하나 더 얻음" } },
	{ id = "bulwark", name = "보호막", icon = "golden_apple", lore = { "시작할 때와 처치할 때", "보호막 2칸" } },
} :: { Perk }

Kits.DefaultKit = "explorer"
Kits.DefaultPerk = "lifesteal"

local kitIndex: { [string]: Kit } = {}
for _, kit in Kits.List do
	kitIndex[kit.id] = kit
end
local perkIndex: { [string]: Perk } = {}
for _, perk in Kits.Perks do
	perkIndex[perk.id] = perk
end

function Kits.get(id: string?): Kit?
	return if id then kitIndex[id] else nil
end

function Kits.getPerk(id: string?): Perk?
	return if id then perkIndex[id] else nil
end

return Kits
