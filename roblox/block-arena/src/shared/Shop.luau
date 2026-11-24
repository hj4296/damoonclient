-- 베드워즈 상점과 팀 강화 목록. "$team" 은 구매한 사람의 팀 색으로 바뀝니다.
local Shop = {}

export type Cost = { item: string, amount: number }
export type ShopItem = {
	key: string,
	name: string?,
	give: string?,
	count: number?,
	cost: Cost?,
	icon: string?, -- 아이콘으로 보여 줄 아이템 id
	sword: boolean?,
	armor: string?, -- 영구 갑옷 단계
	tiers: { { give: string, cost: Cost } }?, -- 단계가 오르는 도구
	toolKind: string?,
	permanent: boolean?,
	lore: { string }?,
}

local function cost(item: string, amount: number): Cost
	return { item = item, amount = amount }
end

Shop.Categories = {
	{
		id = "blocks",
		name = "블록",
		icon = "wool_red",
		items = {
			{ key = "wool", give = "wool_$team", count = 16, cost = cost("iron", 4), lore = { "빨리 쌓고 빨리 부서짐" } },
			{ key = "clay", give = "clay_$team", count = 16, cost = cost("iron", 12), lore = { "단단한 방어용 블록" } },
			{ key = "blast_glass", give = "blast_glass", count = 4, cost = cost("iron", 12), lore = { "폭발에 부서지지 않음" } },
			{ key = "planks", give = "planks", count = 16, cost = cost("gold", 4), lore = { "도끼로 빨리 부서짐" } },
			{ key = "obsidian", give = "obsidian", count = 4, cost = cost("emerald", 4), lore = { "가장 단단한 블록" } },
		},
	},
	{
		id = "melee",
		name = "근접 무기",
		icon = "iron_sword",
		items = {
			{ key = "stone_sword", give = "stone_sword", cost = cost("iron", 10), sword = true },
			{ key = "iron_sword", give = "iron_sword", cost = cost("gold", 7), sword = true },
			{ key = "diamond_sword", give = "diamond_sword", cost = cost("emerald", 4), sword = true },
		},
	},
	{
		id = "armor",
		name = "갑옷",
		icon = "iron_chestplate",
		items = {
			{
				key = "chain_armor",
				name = "사슬 갑옷 (영구)",
				armor = "chain",
				icon = "chain_leggings",
				cost = cost("iron", 30),
				lore = { "레깅스와 부츠가 바뀜", "죽어도 유지" },
			},
			{
				key = "iron_armor",
				name = "철 갑옷 (영구)",
				armor = "iron",
				icon = "iron_leggings",
				cost = cost("gold", 12),
				lore = { "레깅스와 부츠가 바뀜", "죽어도 유지" },
			},
			{
				key = "diamond_armor",
				name = "다이아몬드 갑옷 (영구)",
				armor = "diamond",
				icon = "diamond_leggings",
				cost = cost("emerald", 6),
				lore = { "레깅스와 부츠가 바뀜", "죽어도 유지" },
			},
		},
	},
	{
		id = "tools",
		name = "도구",
		icon = "iron_pickaxe",
		items = {
			{ key = "shears", give = "shears", cost = cost("iron", 20), permanent = true, lore = { "양털을 빨리 부숨", "죽어도 유지" } },
			{
				key = "pickaxe",
				name = "곡괭이",
				toolKind = "pickaxe",
				tiers = {
					{ give = "wood_pickaxe", cost = cost("iron", 10) },
					{ give = "iron_pickaxe", cost = cost("gold", 3) },
					{ give = "diamond_pickaxe", cost = cost("gold", 6) },
				},
				lore = { "살 때마다 한 단계 올라감", "죽으면 한 단계 내려감" },
			},
			{
				key = "axe",
				name = "도끼",
				toolKind = "axe",
				tiers = {
					{ give = "wood_axe", cost = cost("iron", 10) },
					{ give = "iron_axe", cost = cost("gold", 3) },
					{ give = "diamond_axe", cost = cost("gold", 6) },
				},
				lore = { "살 때마다 한 단계 올라감", "죽으면 한 단계 내려감" },
			},
		},
	},
	{
		id = "ranged",
		name = "원거리",
		icon = "bow",
		items = {
			{ key = "arrows", give = "arrow", count = 8, cost = cost("gold", 2) },
			{ key = "bow", give = "bow", cost = cost("gold", 12) },
			{ key = "snowball", give = "snowball", count = 4, cost = cost("iron", 16) },
		},
	},
	{
		id = "utility",
		name = "유틸리티",
		icon = "tnt",
		items = {
			{ key = "golden_apple", give = "golden_apple", cost = cost("gold", 3) },
			{ key = "fireball", give = "fireball", cost = cost("iron", 40) },
			{ key = "tnt", give = "tnt", cost = cost("gold", 4), lore = { "설치하면 3초 뒤 폭발" } },
			{ key = "pearl", give = "pearl", cost = cost("emerald", 4) },
		},
	},
}

Shop.Upgrades = {
	{
		key = "sharpness",
		name = "날카로운 검",
		icon = "iron_sword",
		costs = { 4 },
		lore = { "팀 전체의 검 공격력 +1" },
	},
	{
		key = "protection",
		name = "강화 갑옷",
		icon = "iron_chestplate",
		costs = { 2, 4, 8 },
		lore = { "단계마다 받는 피해 6% 감소" },
	},
	{
		key = "forge",
		name = "강화 용광로",
		icon = "gold",
		costs = { 2, 4 },
		lore = { "섬 자원 생성 +50% / +100%" },
	},
	{
		key = "healpool",
		name = "치유의 샘",
		icon = "golden_apple",
		costs = { 1 },
		lore = { "기지 근처에서 체력이 빨리 참" },
	},
}

local itemIndex: { [string]: ShopItem } = {}
for _, category in Shop.Categories do
	for _, item in category.items do
		itemIndex[item.key] = item :: any
	end
end
local upgradeIndex = {}
for _, upgrade in Shop.Upgrades do
	upgradeIndex[upgrade.key] = upgrade
end

function Shop.getItem(key: string): ShopItem?
	return itemIndex[key]
end

function Shop.getUpgrade(key: string)
	return upgradeIndex[key]
end

function Shop.resolve(id: string, team: string?): string
	return (string.gsub(id, "%$team", team or "red"))
end

return Shop
