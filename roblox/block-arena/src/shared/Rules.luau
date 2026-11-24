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
-- speed 는 효과 배율 (광부의 손 강화 1.25/1.5, 피로 함정 0.3)
function Rules.breakTime(blockId: string, itemId: string?, speed: number?): number?
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
	return math.max(0.05, t / math.max(0.05, speed or 1))
end

-- 광부의 손 단계와 피로 효과로 정해지는 부수기 속도 배율
function Rules.breakSpeed(haste: number, fatigued: boolean): number
	local speed = 1 + 0.25 * haste
	if fatigued then
		speed *= 0.3
	end
	return speed
end

-- 근접 공격 넉백 배율 (넉백 막대기는 훨씬 멀리)
function Rules.knockbackMultiplier(itemId: string?): number
	local item = if itemId then Items.get(itemId) else nil
	return if item and item.knockback then item.knockback else 1
end

-- 화살 피해: 당긴 정도(0~1)와 활의 힘 마법
function Rules.arrowDamage(charge: number, bowId: string?): number
	local damage = 1 + 5 * charge
	if charge >= 0.99 then
		damage += 1
	end
	local bow = if bowId then Items.get(bowId) else nil
	if bow and bow.power then
		damage *= bow.power
	end
	return damage
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
