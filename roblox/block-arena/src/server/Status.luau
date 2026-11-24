-- 상태 효과 (물약, 함정, 불). 끝나는 시각을 플레이어 속성 "FX_<이름>" 에 서버 시각(GetServerTimeNow)으로 적어 두면
-- 모든 클라이언트가 그 값을 보고 이동 속도, 점프, 화면 어둡게, 투명 같은 걸 맞춥니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Config = require(ReplicatedStorage.Shared.Config)

local Status = {}

local effects: { [Player]: { [string]: number } } = {}

local function now(): number
	return workspace:GetServerTimeNow()
end

function Status.add(player: Player, name: string, duration: number)
	if not Config.Effects[name] or duration <= 0 then
		return
	end
	local list = effects[player]
	if not list then
		list = {}
		effects[player] = list
	end
	local ends = math.max(list[name] or 0, now() + duration)
	list[name] = ends
	player:SetAttribute("FX_" .. name, ends)
end

function Status.has(player: Player, name: string): boolean
	local list = effects[player]
	local ends = list and list[name]
	return ends ~= nil and ends > now()
end

function Status.remaining(player: Player, name: string): number
	local list = effects[player]
	local ends = list and list[name]
	return if ends then math.max(0, ends - now()) else 0
end

function Status.remove(player: Player, name: string)
	local list = effects[player]
	if list then
		list[name] = nil
	end
	player:SetAttribute("FX_" .. name, nil)
end

function Status.clear(player: Player)
	effects[player] = nil
	for name in Config.Effects do
		player:SetAttribute("FX_" .. name, nil)
	end
end

-- 끝난 효과 정리 (Combat 의 0.5초 반복에서 부름)
function Status.sweep()
	local t = now()
	for player, list in effects do
		if not player.Parent then
			effects[player] = nil
		else
			for name, ends in list do
				if ends <= t then
					list[name] = nil
					player:SetAttribute("FX_" .. name, nil)
				end
			end
		end
	end
end

return Status
