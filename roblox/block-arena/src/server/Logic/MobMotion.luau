-- 땅 몹 움직임 계산 (순수 계산이라 테스트로 바로 확인): 걷기, 한 칸 오르기, 두 칸 벽에서 멈춤, 떨어지기, 착지
local MobMotion = {}

MobMotion.Gravity = 90
MobMotion.MaxFall = 120
MobMotion.Friction = 6

export type WorldLike = {
	toCell: (any, Vector3) -> (number, number, number),
	isSolid: (any, number, number, number) -> boolean,
	cellCenter: (any, number, number, number) -> Vector3,
}

-- feet: 발 위치, velocity: 넉백 등으로 받은 속도(+낙하 속도), wish: 걸어가려는 속도
function MobMotion.ground(world: any, feet: Vector3, velocity: Vector3, wish: Vector3, dt: number, blockSize: number): (Vector3, Vector3)
	local B = blockSize
	-- 넉백으로 받은 속도는 점점 줄어듦
	local horizontal = Vector3.new(velocity.X, 0, velocity.Z) * math.max(0, 1 - MobMotion.Friction * dt)
	local vy = math.max(-MobMotion.MaxFall, velocity.Y - MobMotion.Gravity * dt)
	local move = (wish + horizontal) * dt

	-- 앞이 막혔으면 한 칸 오르기, 두 칸이면 멈춤
	if move.Magnitude > 0 then
		local nextFeet = feet + move
		local x, y, z = world:toCell(nextFeet + Vector3.new(0, B / 2 - 0.2, 0))
		if world:isSolid(x, y, z) then
			if not world:isSolid(x, y + 1, z) and not world:isSolid(x, y + 2, z) and vy <= 0.1 then
				local center = world:cellCenter(x, y, z)
				nextFeet = Vector3.new(nextFeet.X, center.Y + B / 2, nextFeet.Z)
				vy = 0
			else
				nextFeet = feet
				horizontal = Vector3.zero
			end
		end
		feet = nextFeet
	end
	feet += Vector3.new(0, vy * dt, 0)
	local fx, fy, fz = world:toCell(feet + Vector3.new(0, -0.05, 0))
	if vy <= 0 and world:isSolid(fx, fy, fz) then
		local center = world:cellCenter(fx, fy, fz)
		feet = Vector3.new(feet.X, center.Y + B / 2, feet.Z)
		vy = 0
	end
	return feet, Vector3.new(horizontal.X, vy, horizontal.Z)
end

return MobMotion
