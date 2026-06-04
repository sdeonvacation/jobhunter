import type { JobSkill, SkillCategory } from '../types';

interface TechStackProps {
  skills: JobSkill[];
}

const categoryLabels: Record<SkillCategory, string> = {
  LANGUAGE: 'Languages',
  FRAMEWORK: 'Frameworks',
  DATABASE: 'Databases',
  CLOUD: 'Cloud',
  TOOL: 'Tools',
  METHODOLOGY: 'Methodologies',
  SOFT_SKILL: 'Soft Skills',
};

export default function TechStack({ skills }: TechStackProps) {
  const grouped = skills.reduce(
    (acc, skill) => {
      const cat = skill.category || 'TOOL';
      if (!acc[cat]) acc[cat] = [];
      acc[cat].push(skill);
      return acc;
    },
    {} as Record<SkillCategory, JobSkill[]>,
  );

  return (
    <div className="space-y-3">
      {(Object.entries(grouped) as [SkillCategory, JobSkill[]][]).map(([category, catSkills]) => (
        <div key={category}>
          <p className="text-xs font-medium text-text-muted mb-1.5 uppercase tracking-wide">
            {categoryLabels[category]}
          </p>
          <div className="flex flex-wrap gap-1.5">
            {catSkills.map((skill) => (
              <span
                key={skill.id}
                className={`text-xs px-2.5 py-1 rounded-full border transition-colors ${
                  skill.isRequired
                    ? 'bg-accent/10 text-accent border-accent/30'
                    : 'bg-surface-700 text-text-secondary border-surface-600 opacity-75'
                }`}
              >
                {skill.skillName}
                {!skill.isRequired && (
                  <span className="text-text-muted ml-1">(nice)</span>
                )}
              </span>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
