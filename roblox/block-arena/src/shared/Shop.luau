-- 베드워즈 상점(빠른 구매 포함), 팀 강화, 함정 목록. "$team" 은 구매한 사람의 팀 색으로 바뀝니다.
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

-- 빠른 구매: 자주 사는 물건을 한 화면에 모아 둔 탭 (다른 탭의 물건을 key 로 가리킴)
Shop.QuickBuy = {
	"wool",
	"stone_sword",
	"chain_armor",
	"pickaxe",
	"bow",
	"speed_potion",
	"tnt",
	"popup_tower",
	"golden_apple",
	"clay",
	"iron_sword",
	"iron_armor",
	"shears",
	"arrows",
	"jump_potion",
	"water_bucket",
	"fireball",
	"pearl",
}

Shop.Categories = {
	{
		id = "quick",
		name = "빠른 구매",
		icon = "compass",
		quick = true,
		items = {},
	},
	{
		id = "blocks",
		name = "블록",
		icon = "wool_$team",
		items = {
			{ key = "wool", give = "wool_$team", count = 16, cost = cost("iron", 4), lore = { "빨리 쌓고 빨리 부서짐" } },
			{ key = "clay", give = "clay_$team", count = 16, cost = cost("iron", 12), lore = { "단단한 방어용 블록" } },
			{ key = "blast_glass", give = "blast_glass", count = 4, cost = cost("iron", 12), lore = { "폭발에 부서지지 않음" } },
			{ key = "hardstone", give = "hardstone", count = 12, cost = cost("iron", 24), lore = { "폭발에 강하고 곡괭이로만 잘 부서짐" } },
			{ key = "ladder", give = "ladder", count = 8, cost = cost("iron", 4), lore = { "벽에 붙여 놓고 타고 올라감" } },
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
			{ key = "kb_stick", give = "kb_stick", cost = cost("gold", 5) },
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
			{ key = "bow_power", give = "bow_power", cost = cost("gold", 20) },
			{ key = "bow_punch", give = "bow_punch", cost = cost("emerald", 6) },
			{ key = "snowball", give = "snowball", count = 4, cost = cost("iron", 16) },
		},
	},
	{
		id = "potions",
		name = "물약",
		icon = "speed_potion",
		items = {
			{ key = "speed_potion", give = "speed_potion", cost = cost("emerald", 1) },
			{ key = "jump_potion", give = "jump_potion", cost = cost("emerald", 1) },
			{ key = "invis_potion", give = "invis_potion", cost = cost("emerald", 2) },
		},
	},
	{
		id = "utility",
		name = "유틸리티",
		icon = "tnt",
		items = {
			{ key = "golden_apple", give = "golden_apple", cost = cost("gold", 3) },
			{ key = "bed_bug", give = "bed_bug", cost = cost("iron", 24) },
			{ key = "golem_egg", give = "golem_egg", cost = cost("iron", 120) },
			{ key = "fireball", give = "fireball", cost = cost("iron", 40) },
			{ key = "tnt", give = "tnt", cost = cost("gold", 4), lore = { "설치하면 3초 뒤 폭발" } },
			{ key = "pearl", give = "pearl", cost = cost("emerald", 4) },
			{ key = "water_bucket", give = "water_bucket", cost = cost("gold", 3) },
			{ key = "bridge_egg", give = "bridge_egg", cost = cost("emerald", 1) },
			{ key = "magic_milk", give = "magic_milk", cost = cost("gold", 4) },
			{ key = "sponge", give = "sponge", count = 4, cost = cost("gold", 3), lore = { "주변의 물을 빨아들임" } },
			{ key = "popup_tower", give = "popup_tower", cost = cost("iron", 24) },
		},
	},
}

-- 다이아몬드로 사는 팀 강화. costs 의 개수가 최대 단계, levels 는 단계별 설명
Shop.Upgrades = {
	{
		key = "sharpness",
		name = "날카로운 검",
		icon = "iron_sword",
		costs = { 4 },
		levels = { "팀 전체의 검 공격력 +1" },
	},
	{
		key = "protection",
		name = "강화 갑옷",
		icon = "iron_chestplate",
		costs = { 2, 4, 8, 16 },
		levels = { "받는 피해 6% 감소", "받는 피해 12% 감소", "받는 피해 18% 감소", "받는 피해 24% 감소" },
	},
	{
		key = "haste",
		name = "광부의 손",
		icon = "iron_pickaxe",
		costs = { 2, 4 },
		levels = { "팀 전체 블록 부수는 속도 +25%", "팀 전체 블록 부수는 속도 +50%" },
	},
	{
		key = "forge",
		name = "강화 용광로",
		icon = "gold",
		costs = { 2, 4, 6, 8 },
		levels = { "철·금 생성 +50%", "철·금 생성 +100%", "섬 생성기에서 에메랄드 생성", "철·금 생성 +200%" },
	},
	{
		key = "healpool",
		name = "치유의 샘",
		icon = "golden_apple",
		costs = { 1 },
		levels = { "기지 근처에서 체력이 빨리 참" },
	},
	{
		key = "dragon",
		name = "용의 가호",
		icon = "fireball",
		costs = { 5 },
		levels = { "서든데스 때 우리 팀 용이 한 마리 더 나옴" },
	},
}

-- 함정: 산 순서대로 줄을 서고, 적이 기지에 들어오면 맨 앞의 함정이 발동합니다.
-- 가격은 줄에 이미 있는 함정 수에 따라 1, 2, 4 다이아몬드
Shop.Traps = {
	{ key = "alarm", name = "경보 함정", icon = "compass", lore = { "들어온 적의 투명화를 풀고", "팀에게 알림" } },
	{ key = "fatigue", name = "피로 함정", icon = "wood_pickaxe", lore = { "들어온 적이 10초 동안", "블록을 아주 느리게 부숨" } },
	{ key = "blind", name = "어둠 함정", icon = "invis_potion", lore = { "들어온 적이 8초 동안", "앞이 안 보이고 느려짐" } },
	{ key = "counter", name = "반격 함정", icon = "speed_potion", lore = { "기지에 있는 우리 팀이 15초 동안", "빨라지고 높이 뜀" } },
}
Shop.TrapCosts = { 1, 2, 4 }
Shop.MaxTraps = 3

local itemIndex: { [string]: ShopItem } = {}
for _, category in Shop.Categories do
	for _, item in category.items do
		itemIndex[item.key] = item :: any
	end
end
-- 빠른 구매 탭은 다른 탭의 물건을 그대로 보여 줌
for _, category in Shop.Categories do
	if category.quick then
		for _, key in Shop.QuickBuy do
			local item = itemIndex[key]
			assert(item, "빠른 구매에 없는 물건: " .. key)
			table.insert(category.items, item)
		end
	end
end
local upgradeIndex = {}
for _, upgrade in Shop.Upgrades do
	upgradeIndex[upgrade.key] = upgrade
end
local trapIndex = {}
for _, trap in Shop.Traps do
	trapIndex[trap.key] = trap
end

function Shop.getItem(key: string): ShopItem?
	return itemIndex[key]
end

function Shop.getUpgrade(key: string)
	return upgradeIndex[key]
end

function Shop.getTrap(key: string)
	return trapIndex[key]
end

function Shop.trapCost(queued: number): number?
	return Shop.TrapCosts[queued + 1]
end

function Shop.resolve(id: string, team: string?): string
	return (string.gsub(id, "%$team", team or "red"))
end

return Shop
