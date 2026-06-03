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

const categoryColors: Record<SkillCategory, string> = {
  LANGUAGE: 'bg-blue-100 text-blue-800',
  FRAMEWORK: 'bg-purple-100 text-purple-800',
  DATABASE: 'bg-green-100 text-green-800',
  CLOUD: 'bg-orange-100 text-orange-800',
  TOOL: 'bg-gray-100 text-gray-800',
  METHODOLOGY: 'bg-pink-100 text-pink-800',
  SOFT_SKILL: 'bg-teal-100 text-teal-800',
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
          <p className="text-xs font-medium text-gray-500 mb-1">
            {categoryLabels[category]}
          </p>
          <div className="flex flex-wrap gap-1">
            {catSkills.map((skill) => (
              <span
                key={skill.id}
                className={`text-xs px-2 py-0.5 rounded ${categoryColors[category]} ${
                  !skill.isRequired ? 'opacity-60' : ''
                }`}
              >
                {skill.skillName}
                {!skill.isRequired && ' (nice-to-have)'}
              </span>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
