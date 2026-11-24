-- 팀 나누기: 사람 수가 가장 적은 팀부터 채워서 인원을 고르게 맞춥니다.
local Teams = {}

function Teams.assign<T>(players: { T }, teamCount: number, teamSize: number, random: () -> number): { { T } }
	local shuffled = table.clone(players)
	for i = #shuffled, 2, -1 do
		local j = 1 + math.floor(random() * i)
		j = math.clamp(j, 1, i)
		shuffled[i], shuffled[j] = shuffled[j], shuffled[i]
	end
	local teams = {}
	for i = 1, teamCount do
		teams[i] = {}
	end
	for _, player in shuffled do
		local bestIndex = nil
		for i = 1, teamCount do
			if #teams[i] < teamSize and (bestIndex == nil or #teams[i] < #teams[bestIndex]) then
				bestIndex = i
			end
		end
		if bestIndex then
			table.insert(teams[bestIndex], player)
		end
	end
	return teams
end

return Teams
