UPDATE title_ownership SET title_id = CASE title_id
    WHEN 'phantom' THEN 'chronomancer'
    WHEN 'echo_walker' THEN 'frostbound'
    WHEN 'forgeheart' THEN 'emberforge'
    WHEN 'duelist' THEN 'berserker'
    WHEN 'bulwark' THEN 'colossus'
    WHEN 'harvester' THEN 'mycelial_drifter'
    ELSE title_id END
WHERE title_id IN ('phantom', 'echo_walker', 'forgeheart', 'duelist', 'bulwark', 'harvester');

UPDATE players SET equipped_title = CASE equipped_title
    WHEN 'phantom' THEN 'chronomancer'
    WHEN 'echo_walker' THEN 'frostbound'
    WHEN 'forgeheart' THEN 'emberforge'
    WHEN 'duelist' THEN 'berserker'
    WHEN 'bulwark' THEN 'colossus'
    WHEN 'harvester' THEN 'mycelial_drifter'
    ELSE equipped_title END
WHERE equipped_title IN ('phantom', 'echo_walker', 'forgeheart', 'duelist', 'bulwark', 'harvester');
