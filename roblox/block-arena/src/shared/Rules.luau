-- 서버와 클라이언트가 같이 쓰는 계산식 (채굴 시간, 공격력, 방어력)
local Blocks = require(script.Parent.Blocks)
local Items = require(script.Parent.Items)

local Rules = {}

Rules.ProtectionPerLevel = 0.06
Rules.MaxReduction = 0.8

function Rules.toolMultiplier(blockId: string, itemId: string?): number
	local block = Blocks.get(blockId)
	if not block or not block.tool or not itemId then
		return 1
	end
	local item = Items.get(itemId)
	if item and item.kind == block.tool then
		return item.speed or 1
	end
	return 1
end

-- 블록을 부수는 데 걸리는 시간(초). 부술 수 없으면 nil.
function Rules.breakTime(blockId: string, itemId: string?): number?
	local block = Blocks.get(blockId)
	if not block or block.unbreakable then
		return nil
	end
	local mult = Rules.toolMultiplier(blockId, itemId)
	local t = block.hardness / mult
	if block.tool == "pickaxe" and mult == 1 then
		-- 돌 같은 블록은 맨손이면 훨씬 오래 걸림
		t *= 2.5
	end
	return math.max(0.05, t)
end

function Rules.meleeDamage(itemId: string?, sharpness: number): number
	local item = if itemId then Items.get(itemId) else nil
	if not item or not item.damage then
		return 1
	end
	local damage = item.damage
	if item.kind == "sword" then
		damage += sharpness
	end
	return damage
end

function Rules.armorPoints(armor: { [string]: string? }): number
	local total = 0
	for _, slot in Items.ArmorSlots do
		local id = armor[slot]
		local item = if id then Items.get(id) else nil
		if item and item.armorPoints then
			total += item.armorPoints
		end
	end
	return total
end

function Rules.reduction(points: number, protection: number): number
	return math.clamp(points * 0.04 + protection * Rules.ProtectionPerLevel, 0, Rules.MaxReduction)
end

-- 순수 피해 계산: 갑옷/보호 단계/보호막(흡수)을 적용한 결과
function Rules.applyDamage(
	amount: number,
	health: number,
	absorption: number,
	points: number,
	protection: number,
	ignoreArmor: boolean?
): (number, number, number)
	local dealt = amount
	if not ignoreArmor then
		dealt = amount * (1 - Rules.reduction(points, protection))
	end
	local fromAbsorption = math.min(absorption, dealt)
	absorption -= fromAbsorption
	dealt -= fromAbsorption
	health -= dealt
	return health, absorption, dealt + fromAbsorption
end

return Rules
