-- 럭키 블록을 부쉈을 때 나오는 결과표. weight 가 클수록 자주 나옵니다.
-- kind: items(아이템이 튀어나옴), tnt(불붙은 TNT), mobs(좀벌레 떼), effect(효과), launch(하늘로), lightning(번개), nothing
local LuckyTable = {}

export type Outcome = {
	id: string,
	weight: number,
	kind: string,
	good: boolean,
	message: string,
	items: { { id: string, count: number } }?,
	effects: { { name: string, duration: number } }?,
	count: number?,
}

local function s(id: string, count: number?)
	return { id = id, count = count or 1 }
end

LuckyTable.Outcomes = {
	{ id = "sword", weight = 8, kind = "items", good = true, message = "날카로운 다이아몬드 검!", items = { s("diamond_sword_sharp") } },
	{
		id = "armor",
		weight = 7,
		kind = "items",
		good = true,
		message = "보호 마법이 붙은 철 갑옷 한 벌!",
		items = { s("iron_helmet_prot"), s("iron_chestplate_prot"), s("iron_leggings_prot"), s("iron_boots_prot") },
	},
	{ id = "diamond_piece", weight = 6, kind = "items", good = true, message = "다이아몬드 흉갑!", items = { s("diamond_chestplate_prot") } },
	{ id = "potions", weight = 8, kind = "items", good = true, message = "물약 꾸러미!", items = { s("speed_potion"), s("jump_potion"), s("regen_potion") } },
	{ id = "travel", weight = 7, kind = "items", good = true, message = "순간이동 진주와 황금 사과!", items = { s("pearl", 2), s("golden_apple", 3) } },
	{ id = "archer", weight = 7, kind = "items", good = true, message = "힘 마법 활과 화살!", items = { s("bow_power"), s("arrow", 16) } },
	{ id = "stick", weight = 5, kind = "items", good = true, message = "넉백 막대기!", items = { s("kb_stick") } },
	{ id = "buckets", weight = 5, kind = "items", good = true, message = "물과 용암 양동이!", items = { s("water_bucket"), s("lava_bucket") } },
	{ id = "blocks", weight = 8, kind = "items", good = true, message = "블록 한 무더기!", items = { s("planks", 48), s("hardstone", 16) } },
	{ id = "rod", weight = 5, kind = "items", good = true, message = "낚싯대와 눈덩이!", items = { s("fishing_rod"), s("snowball", 12) } },
	{ id = "bombs", weight = 4, kind = "items", good = true, message = "폭탄 상자!", items = { s("tnt", 4), s("fireball", 2) } },
	{ id = "invis", weight = 3, kind = "items", good = true, message = "투명화 물약!", items = { s("invis_potion") } },
	{ id = "tnt", weight = 6, kind = "tnt", good = false, message = "앗! 불붙은 TNT다!", count = 3 },
	{ id = "bugs", weight = 6, kind = "mobs", good = false, message = "좀벌레 떼가 나타났다!", count = 4 },
	{ id = "dark", weight = 4, kind = "effect", good = false, message = "눈앞이 캄캄해졌다…", effects = { { name = "blind", duration = 6 }, { name = "slow", duration = 6 } } },
	{ id = "rocket", weight = 4, kind = "launch", good = false, message = "하늘로 날아간다!" },
	{ id = "lightning", weight = 3, kind = "lightning", good = false, message = "번개가 내리쳤다!" },
	{ id = "speedy", weight = 4, kind = "effect", good = true, message = "몸이 가벼워졌다!", effects = { { name = "speed", duration = 30 }, { name = "jump", duration = 30 } } },
	{ id = "nothing", weight = 3, kind = "nothing", good = false, message = "…아무것도 없었다." },
} :: { Outcome }

function LuckyTable.pick(random: () -> number): Outcome
	local total = 0
	for _, outcome in LuckyTable.Outcomes do
		total += outcome.weight
	end
	local r = random() * total
	for _, outcome in LuckyTable.Outcomes do
		r -= outcome.weight
		if r < 0 then
			return outcome
		end
	end
	return LuckyTable.Outcomes[#LuckyTable.Outcomes]
end

return LuckyTable
