package dev.jobhub.controller;

import dev.jobhub.model.Company;
import dev.jobhub.model.enums.CompanyStatus;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.service.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {

    @Mock private CompanyRepository companyRepository;
    @Mock private CompanyService companyService;
    @Mock private JobPostingRepository jobPostingRepository;

    private CompanyController controller;

    @BeforeEach
    void setUp() {
        controller = new CompanyController(companyRepository, companyService, jobPostingRepository);
    }

    @Test
    void listCompanies_noFilters_returnsActivePage() {
        Company company = Company.builder().id(UUID.randomUUID()).name("TestCo").status(CompanyStatus.ACTIVE).isActive(true).build();
        Page<Company> page = new PageImpl<>(List.of(company));
        when(companyRepository.findByIsActiveTrue(any(Pageable.class))).thenReturn(page);

        var result = controller.listCompanies(null, null, 0, 20, "priorityScore");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("TestCo");
    }

    @Test
    void listCompanies_withStatus_filtersbyStatus() {
        Company company = Company.builder().id(UUID.randomUUID()).name("ActiveCo").status(CompanyStatus.ACTIVE).isActive(true).build();
        Page<Company> page = new PageImpl<>(List.of(company));
        when(companyRepository.findByStatus(eq(CompanyStatus.ACTIVE), any(Pageable.class))).thenReturn(page);

        var result = controller.listCompanies("ACTIVE", null, 0, 20, "priorityScore");

        assertThat(result.getContent()).hasSize(1);
        verify(companyRepository).findByStatus(eq(CompanyStatus.ACTIVE), any(Pageable.class));
    }

    @Test
    void listCompanies_withSearch_filtersbyName() {
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp").status(CompanyStatus.ACTIVE).isActive(true).build();
        Page<Company> page = new PageImpl<>(List.of(company));
        when(companyRepository.findByIsActiveTrueAndNameContaining(eq("acme"), any(Pageable.class))).thenReturn(page);

        var result = controller.listCompanies(null, "acme", 0, 20, "priorityScore");

        assertThat(result.getContent()).hasSize(1);
        verify(companyRepository).findByIsActiveTrueAndNameContaining(eq("acme"), any(Pageable.class));
    }

    @Test
    void listCompanies_withStatusAndSearch_combinesFilters() {
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp").status(CompanyStatus.DISCOVERED).isActive(true).build();
        Page<Company> page = new PageImpl<>(List.of(company));
        when(companyRepository.findByStatusAndNameContaining(eq(CompanyStatus.DISCOVERED), eq("acme"), any(Pageable.class))).thenReturn(page);

        var result = controller.listCompanies("DISCOVERED", "acme", 0, 20, "priorityScore");

        assertThat(result.getContent()).hasSize(1);
        verify(companyRepository).findByStatusAndNameContaining(eq(CompanyStatus.DISCOVERED), eq("acme"), any(Pageable.class));
    }

    @Test
    void updatePriority_validRequest_updates() {
        UUID id = UUID.randomUUID();
        Company company = Company.builder().id(id).name("TestCo").priorityScore(50.0).build();
        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(companyRepository.save(any(Company.class))).thenReturn(company);

        ResponseEntity<Void> result = controller.updatePriority(id, new CompanyController.PriorityRequest(4));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(company.getPriorityScore()).isEqualTo(4.0);
        verify(companyRepository).save(company);
    }

    @Test
    void updatePriority_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        ResponseEntity<Void> result = controller.updatePriority(id, new CompanyController.PriorityRequest(3));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updatePriority_invalidPriority_returnsBadRequest() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> tooLow = controller.updatePriority(id, new CompanyController.PriorityRequest(0));
        ResponseEntity<Void> tooHigh = controller.updatePriority(id, new CompanyController.PriorityRequest(6));

        assertThat(tooLow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooHigh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(companyRepository, never()).findById(any());
    }

    @Test
    void updatePriority_boundaryValues_accepted() {
        UUID id = UUID.randomUUID();
        Company company = Company.builder().id(id).name("TestCo").priorityScore(50.0).build();
        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(companyRepository.save(any(Company.class))).thenReturn(company);

        ResponseEntity<Void> min = controller.updatePriority(id, new CompanyController.PriorityRequest(1));
        assertThat(min.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(company.getPriorityScore()).isEqualTo(1.0);

        ResponseEntity<Void> max = controller.updatePriority(id, new CompanyController.PriorityRequest(5));
        assertThat(max.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(company.getPriorityScore()).isEqualTo(5.0);
    }
}
