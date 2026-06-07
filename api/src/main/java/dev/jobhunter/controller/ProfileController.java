package dev.jobhunter.controller;

import dev.jobhunter.dto.DtoMapper;
import dev.jobhunter.dto.ProfileDto;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final PersonalProfileLoader profileLoader;

    public ProfileController(PersonalProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    @GetMapping
    public ResponseEntity<ProfileDto> getProfile() {
        PersonalProfile profile = profileLoader.getProfile();
        return ResponseEntity.ok(DtoMapper.toProfile(profile));
    }

    @PutMapping
    public ResponseEntity<ProfileDto> updateProfile(@RequestBody ProfileDto profileDto) {
        // Profile is loaded from YAML - update not supported via API
        // Return current profile as acknowledgment
        PersonalProfile profile = profileLoader.getProfile();
        return ResponseEntity.ok(DtoMapper.toProfile(profile));
    }
}
