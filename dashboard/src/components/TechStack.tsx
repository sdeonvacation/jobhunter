

interface TechStackProps {
  skills: string[];
}

export default function TechStack({ skills }: TechStackProps) {
  if (skills.length === 0) return <div />;

  return (
    <div className="space-y-3">
      <div>
        <p className="text-xs font-medium text-text-muted mb-1.5 uppercase tracking-wide">
          Skills
        </p>
        <div className="flex flex-wrap gap-1.5">
          {skills.map((skill) => (
            <span
              key={skill}
              className="text-xs px-2.5 py-1 rounded-full border bg-accent/10 text-accent border-accent/30 transition-colors"
            >
              {skill}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
